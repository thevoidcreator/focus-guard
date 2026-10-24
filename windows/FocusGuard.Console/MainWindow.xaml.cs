using System.IO;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Text.Json;
using System.Windows;
using System.Windows.Controls;
using FocusGuard.Core;
using Microsoft.Win32;

namespace FocusGuard.ConsoleApp;
public partial class MainWindow : Window {
    private string? request;
    private bool busy;
    private readonly string registry = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "FocusGuard", "phones-v2.json");
    private string Root => (Drives.SelectedItem as DriveInfo)?.RootDirectory.FullName ?? throw new InvalidOperationException("Выберите подключённую флешку");
    private string Folder => Keys.SelectedItem as string ?? throw new InvalidOperationException("Выберите существующий ключ");
    public MainWindow() { InitializeComponent(); LoadDrives(); }
    private void LoadDrives() {
        Drives.ItemsSource = DriveInfo.GetDrives().Where(d => d.IsReady && d.DriveType == DriveType.Removable).ToArray();
        if (Drives.Items.Count > 0) Drives.SelectedIndex = 0;
    }
    private void Refresh(object sender, RoutedEventArgs e) { if (!busy) LoadDrives(); }
    private void DriveChanged(object sender, SelectionChangedEventArgs e) {
        if (busy) return;
        try {
            string parent = Path.Combine(Root, UsbKey.Folder);
            Keys.ItemsSource = Directory.Exists(parent) ? Directory.GetDirectories(parent).Where(f => File.Exists(Path.Combine(f, "public.json")) && UsbKey.ReadMetadata(f).Role == "main").ToArray() : [];
            if (Keys.Items.Count > 0) Keys.SelectedIndex = 0;
            request = null; Confirm.IsChecked = false;
        } catch(Exception ex) { Status.Text = ex.Message; }
    }
    private void KeyChanged(object sender, SelectionChangedEventArgs e) {
        try { if (Keys.SelectedItem != null) KeyDetails.Text = "Идентичность флешки: " + UsbKey.ReadMetadata(Folder).KeyId; request = null; Confirm.IsChecked = false; }
        catch(Exception ex) { Status.Text = ex.Message; }
    }
    private static char[] ReadPassword(PasswordBox box) {
        using var secure = box.SecurePassword; IntPtr p = Marshal.SecureStringToBSTR(secure);
        try { var result = new char[secure.Length]; Marshal.Copy(p, result, 0, result.Length); return result; }
        finally { Marshal.ZeroFreeBSTR(p); box.Clear(); }
    }
    private async Task Run(Func<char[],string> action, Action<string> complete) {
        if (busy) return; busy = true; char[] password = ReadPassword(Password);
        try { Status.Text = "Проверяем флешку…"; var result = await Task.Run(() => action(password)); complete(result); }
        catch(Exception ex) { Status.Text = "Не выполнено: " + ex.Message; }
        finally { Array.Clear(password); busy = false; }
    }
    private async void CreateKey(object sender, RoutedEventArgs e) {
        var repeat = ReadPassword(RepeatPassword);
        try { string root = Root; string? created = null; await Run(password => {
            if (!password.AsSpan().SequenceEqual(repeat)) throw new ArgumentException("Пароли не совпадают");
            return UsbKey.Create(root, password, "main");
        }, folder => { created = folder; Status.Text = "Ключ создан. Сохраните публичный файл для телефона."; });
        LoadDrives(); if (created != null) Keys.SelectedItem = created; } catch(Exception ex) { Status.Text = ex.Message; } finally { Array.Clear(repeat); }
    }
    private async void VerifyKey(object sender, RoutedEventArgs e) {
        try { string folder = Folder; await Run(password => {
            if (!UsbKey.RemovablePresent(Path.GetPathRoot(folder)!)) throw new IOException("Подключите флешку");
            using var key = UsbKey.Open(folder, password); var data = RandomNumberGenerator.GetBytes(32);
            var sig = key.SignData(data, HashAlgorithmName.SHA256);
            using var pub = Protocol.PublicKey(UsbKey.ReadMetadata(folder).PublicSpki);
            if (!pub.VerifyData(data, sig, HashAlgorithmName.SHA256) || !UsbKey.RemovablePresent(Path.GetPathRoot(folder)!)) throw new CryptographicException("Проверка подписи не прошла");
            return "Флешка исправна. Существующий ключ можно использовать в новой версии.";
        }, text => Status.Text = text); } catch(Exception ex) { Status.Text = ex.Message; }
    }
    private void ExportPublic(object sender, RoutedEventArgs e) {
        try { string text = JsonSerializer.Serialize(UsbKey.ReadMetadata(Folder), Protocol.Json); Save(text, "FocusGuard-usb-public.json"); }
        catch(Exception ex) { Status.Text = ex.Message; }
    }
    private Dictionary<string,string> Pins() => File.Exists(registry) ? JsonSerializer.Deserialize<Dictionary<string,string>>(UsbKey.ReadBounded(registry, Protocol.MaxInput), Protocol.Json) ?? throw new FormatException("Реестр повреждён") : new();
    private void OpenRequest(object sender, RoutedEventArgs e) {
        if (busy) return; request = null; Confirm.IsChecked = false;
        var dialog = new OpenFileDialog { Filter = "Файл Focus Guard|*.json|Все файлы|*.*" };
        if (dialog.ShowDialog() != true) return;
        try {
            string token = CompanionProtocol.ReadFile(UsbKey.ReadBounded(dialog.FileName, Protocol.MaxInput));
            var m = CompanionProtocol.Inspect(token); CompanionProtocol.Verify(token, m.PhoneSpki);
            if (m.Phase != "request" || m.Digest != "" || m.SignerSpki != UsbKey.ReadMetadata(Folder).PublicSpki) throw new InvalidOperationException("Запрос не для выбранной флешки");
            var label = CompanionProtocol.Label(m.Action);
            var pins = Pins();
            if (pins.TryGetValue(m.PhoneId, out var known) && known != m.PhoneSpki) throw new InvalidOperationException("Идентичность телефона изменилась");
            if (!pins.ContainsKey(m.PhoneId)) {
                if (MessageBox.Show($"Сверьте данные внизу экрана Focus Guard на телефоне:\nТелефон: {m.PhoneId}\nИдентичность: {Protocol.KeyId(m.PhoneSpki)}\nСовпадают?", "Первое подключение этого телефона", MessageBoxButton.YesNo) != MessageBoxResult.Yes) return;
                pins[m.PhoneId] = m.PhoneSpki; Directory.CreateDirectory(Path.GetDirectoryName(registry)!);
                string temp = registry + ".tmp"; File.WriteAllText(temp, JsonSerializer.Serialize(pins, Protocol.Json)); File.Move(temp, registry, true);
            }
            RequestDetails.Text = $"{label}\nТелефон: {m.PhoneId}\nЗапрос: {m.RequestId}\nПодпись телефона проверена.";
            request = token; Status.Text = "Проверьте действие, отметьте галочку и введите пароль флешки.";
        } catch(Exception ex) { Status.Text = "Запрос отклонён: " + ex.Message; }
    }
    private async void Approve(object sender, RoutedEventArgs e) {
        if (request == null || Confirm.IsChecked != true) { Status.Text = "Откройте запрос и подтвердите действие галочкой"; return; }
        try { string token = request; string folder = Folder;
            await Run(password => CompanionProtocol.Respond(token, folder, password), text => Save(text, "FocusGuard-response.fg2.json"));
            Confirm.IsChecked = false; request = null;
        } catch(Exception ex) { Status.Text = ex.Message; }
    }
    private void Save(string text, string name) {
        var dialog = new SaveFileDialog { FileName = name, Filter = "Файл Focus Guard|*.json" };
        if (dialog.ShowDialog() == true) { File.WriteAllText(dialog.FileName, text); Status.Text = "Файл сохранён. Передайте его телефону и примените в Focus Guard."; }
    }
}
