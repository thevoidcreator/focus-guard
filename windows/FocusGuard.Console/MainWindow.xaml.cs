using System.IO;
using System.Runtime.InteropServices;
using System.Security;
using System.Security.Cryptography;
using System.Text.Json;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using FocusGuard.Core;
using Microsoft.Win32;
using ZXing;
using ZXing.Common;

namespace FocusGuard.ConsoleApp;
public partial class MainWindow : Window
{
    private string page = "setup";
    private Envelope? verified;
    private string verifiedText = "";
    private readonly string registryPath = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "FocusGuard", "phones.json");
    private bool busy;
    private sealed record PhonePin(string PublicSpki, string KeyId);
    public MainWindow() { InitializeComponent(); LoadDrives(); }
    private string Root => (Drives.SelectedItem as DriveInfo)?.RootDirectory.FullName ?? throw new InvalidOperationException("Выберите съёмный USB-том");
    private string Folder => Keys.SelectedItem as string ?? throw new InvalidOperationException("Выберите папку ключа");
    private void Navigate(object sender, RoutedEventArgs e) {
        if (busy) return;
        page = (string)((Button)sender).Tag; Heading.Text = ((Button)sender).Content.ToString();
        SetupPanel.Visibility = page == "setup" ? Visibility.Visible : Visibility.Collapsed;
        VerifyPanel.Visibility = page == "verify" ? Visibility.Visible : Visibility.Collapsed;
        RequestPanel.Visibility = page is not ("setup" or "verify") ? Visibility.Visible : Visibility.Collapsed;
        verified = null; Confirm.IsChecked = false; Output.Text = ""; Qr.Source = null; Password.Clear(); RepeatPassword.Clear();
    }
    private void LoadDrives() {
        var drives = DriveInfo.GetDrives().Where(d => d.DriveType == DriveType.Removable && d.IsReady).ToArray();
        Drives.ItemsSource = drives; BackupDrives.ItemsSource = drives;
        if (drives.Length > 0) Drives.SelectedIndex = 0;
    }
    private void RefreshDrives(object sender, RoutedEventArgs e) { if (!busy) LoadDrives(); }
    private void DriveChanged(object sender, SelectionChangedEventArgs e) {
        try {
            var d = (DriveInfo)Drives.SelectedItem;
            if (d == null) return;
            DriveDetails.Text = $"{d.RootDirectory.FullName} • {d.VolumeLabel} • свободно {d.AvailableFreeSpace / 1048576} МБ";
            var path = Path.Combine(Root, UsbKey.Folder);
            Keys.ItemsSource = Directory.Exists(path) ? Directory.GetDirectories(path).Where(p => File.Exists(Path.Combine(p, "public.json"))).ToArray() : [];
            if (Keys.Items.Count > 0) Keys.SelectedIndex = 0;
        } catch (Exception ex) { Status.Text = "Носитель недоступен: " + ex.Message; }
    }
    private void KeyChanged(object sender, SelectionChangedEventArgs e) {
        try { if (Keys.SelectedItem == null) return; var m = UsbKey.ReadMetadata(Folder); KeyDetails.Text = $"{m.Role}\nFingerprint SHA-256 SPKI: {m.KeyId}\n{Folder}"; Show(JsonSerializer.Serialize(m, Protocol.Json)); }
        catch (Exception ex) { Status.Text = "Ключ не прочитан: " + ex.Message; }
    }
    private static char[] ReadPassword(PasswordBox box) {
        using SecureString secure = box.SecurePassword;
        IntPtr p = Marshal.SecureStringToBSTR(secure);
        try { var chars = new char[secure.Length]; Marshal.Copy(p, chars, 0, chars.Length); return chars; }
        finally { Marshal.ZeroFreeBSTR(p); box.Clear(); }
    }
    private async Task RunUsb(Func<char[], Func<bool>, string> operation) {
        if (busy) return;
        char[] chars = ReadPassword(Password); string root;
        try { root = Root; } catch (Exception ex) { Array.Clear(chars); Status.Text = ex.Message; return; }
        busy = true; using var cancellation = new CancellationTokenSource();
        var monitor = Task.Run(async () => {
            try { while (!cancellation.IsCancellationRequested) { if (!UsbKey.RemovablePresent(root)) { cancellation.Cancel(); return; } await Task.Delay(75, cancellation.Token); } }
            catch (OperationCanceledException) { }
        });
        try {
            Status.Text = "Проверка USB и выполнение криптографической операции…";
            string output = await Task.Run(() => operation(chars, () => !cancellation.IsCancellationRequested && UsbKey.RemovablePresent(root)));
            cancellation.Token.ThrowIfCancellationRequested(); Show(output); Status.Text = page is "setup" or "verify" ? "Ключ проверен. Сверьте fingerprint на телефоне." : "Готово. Ответ действует только в срок запроса телефона и потребляется один раз.";
        } catch (Exception ex) { Output.Text = ""; Qr.Source = null; Status.Text = "Операция отклонена: " + ex.Message; }
        finally { Array.Clear(chars); cancellation.Cancel(); await monitor; busy = false; }
    }
    private async void CreateKey(object sender, RoutedEventArgs e) {
        char[] repeated = ReadPassword(RepeatPassword);
        string root; string role = NewRole.SelectedIndex == 0 ? "main" : "emergency";
        try { root = Root; } catch (Exception ex) { Array.Clear(repeated); Status.Text = ex.Message; return; }
        if (role == "emergency" && MessageBox.Show("Аварийный ключ должен храниться у доверенного человека на независимом носителе. Продолжить на его устройстве?", "Отдельный аварийный ключ", MessageBoxButton.YesNo) != MessageBoxResult.Yes) { Array.Clear(repeated); Password.Clear(); return; }
        try {
            string? createdFolder = null;
            await RunUsb((password, probe) => {
                if (!password.AsSpan().SequenceEqual(repeated)) throw new ArgumentException("Пароли не совпадают");
                var folder = UsbKey.Create(root, password, role, probe);
                createdFolder = folder;
                return JsonSerializer.Serialize(UsbKey.ReadMetadata(folder), Protocol.Json);
            });
            DriveChanged(this, new SelectionChangedEventArgs(SelectorEvent(), Array.Empty<object>(), Array.Empty<object>()));
            if (createdFolder != null) Keys.SelectedItem = createdFolder;
        } finally { Array.Clear(repeated); }
    }
    private static RoutedEvent SelectorEvent() => System.Windows.Controls.Primitives.Selector.SelectionChangedEvent;
    private Dictionary<string, PhonePin> ReadPins() {
        if (!File.Exists(registryPath)) return new();
        string text = UsbKey.ReadBounded(registryPath, Protocol.MaxInput); Protocol.StrictJson(text);
        return JsonSerializer.Deserialize<Dictionary<string, PhonePin>>(text, Protocol.Json) ?? throw new System.FormatException("Реестр телефонов повреждён");
    }
    private void PinPhone(Envelope envelope, Message m) {
        var pins = ReadPins();
        if (pins.TryGetValue(m.InstallationId, out var known)) {
            if (known.PublicSpki != envelope.PhoneSpki || known.KeyId != m.PhoneKeyId) throw new InvalidOperationException("Идентичность телефона изменилась; автоматическая перепривязка запрещена");
        } else {
            if (MessageBox.Show($"Этот ноутбук ещё не знает телефон. Сверьте его существующую идентичность на экране телефона:\n{m.InstallationId}\n{m.PhoneKeyId}\nСовпадает? Это не меняет USB-ключи или политику телефона.", "Явная сверка fingerprint на новом ноутбуке", MessageBoxButton.YesNo) != MessageBoxResult.Yes) throw new OperationCanceledException("Сверка отменена");
            pins[m.InstallationId] = new PhonePin(envelope.PhoneSpki, m.PhoneKeyId);
            Directory.CreateDirectory(Path.GetDirectoryName(registryPath)!);
            string temp = registryPath + ".tmp";
            File.WriteAllText(temp, JsonSerializer.Serialize(pins, Protocol.Json)); File.Move(temp, registryPath, true);
        }
    }
    private void InspectRequest(object sender, RoutedEventArgs e) {
        try {
            verified = null; Confirm.IsChecked = false;
            var envelope = Protocol.ParseEnvelope(Request.Text.Trim()); var m = Protocol.Inspect(envelope.Request);
            if (m.MessageType != page) throw new InvalidOperationException("Откройте раздел, соответствующий действию запроса");
            var meta = UsbKey.ReadMetadata(Folder);
            if (meta.KeyId != m.KeyId || meta.Role != m.Role) throw new InvalidOperationException("Выбран другой ключ/роль");
            PinPhone(envelope, m);
            RequestDetails.Text = $"Телефон: {m.InstallationId}\nДействие: {m.MessageType} / {m.Phase}\nКлюч: {m.KeyId} ({m.Role})\nЗапрос: {m.RequestId}\nFlow: {m.RemovalFlowId}\nПолитика: {m.PolicyVersion}\n" +
                (m.Proposal != null ? "Новая политика: " + JsonSerializer.Serialize(m.Proposal, Protocol.Json) + "\n" : "") +
                (m.MessageType == "emergency_release" ? "Последствие: немедленный запуск снятия защиты." : m.Phase == "request_finish" ? "Последствие: снятие защиты или применение новой политики после ожидания на телефоне." : "Последствие: регистрация ключа либо начало 48-часового ожидания; приложения остаются закрыты.");
            verified = envelope; verifiedText = Request.Text; Status.Text = "Подпись телефона проверена. Проверьте действие перед подписью.";
        } catch (Exception ex) { Status.Text = "Запрос отклонён: " + ex.Message; }
    }
    private async void SignRequest(object sender, RoutedEventArgs e) {
        if (verified == null || Request.Text != verifiedText || Confirm.IsChecked != true) { Status.Text = "Проверьте текущий запрос и подтвердите действие"; return; }
        string folder = Folder; var envelope = verified;
        await RunUsb((password, probe) => UsbKey.Sign(folder, password, envelope, probe)); Confirm.IsChecked = false;
    }
    private async void VerifyKey(object sender, RoutedEventArgs e) {
        try {
            string folder = Folder;
            await RunUsb((password, probe) => {
                if (!probe()) throw new IOException("USB отсутствует");
                using var key = UsbKey.Open(folder, password); var bytes = RandomNumberGenerator.GetBytes(32);
                var signature = key.SignData(bytes, HashAlgorithmName.SHA256, DSASignatureFormat.IeeeP1363FixedFieldConcatenation);
                using var pub = Protocol.PublicKey(UsbKey.ReadMetadata(folder).PublicSpki);
                if (!pub.VerifyData(bytes, signature, HashAlgorithmName.SHA256, DSASignatureFormat.IeeeP1363FixedFieldConcatenation) || !probe()) throw new CryptographicException();
                return JsonSerializer.Serialize(UsbKey.ReadMetadata(folder), Protocol.Json);
            });
        } catch (Exception ex) { Status.Text = ex.Message; }
    }
    private void BackupKey(object sender, RoutedEventArgs e) {
        if (busy || BackupAck.IsChecked != true) { Status.Text = "Подтвердите полномочия копии"; return; }
        try { var target = (DriveInfo?)BackupDrives.SelectedItem ?? throw new InvalidOperationException("Выберите другой носитель"); UsbKey.Backup(Folder, target.RootDirectory.FullName); Status.Text = "Копия сверена. На другом ноутбуке выполните «Проверить ключ» и реальное подтверждение телефоном."; }
        catch (Exception ex) { Status.Text = "Копирование не завершено: " + ex.Message; }
    }
    private void ScanCamera(object sender, RoutedEventArgs e) { if (busy) return; var scanner = new CameraWindow { Owner = this }; if (scanner.ShowDialog() == true) Request.Text = scanner.Result; }
    private void ImportRequest(object sender, RoutedEventArgs e) {
        var dialog = new OpenFileDialog { Filter = "Запрос Focus Guard|*.fgreq;*.json|Все файлы|*.*" };
        if (dialog.ShowDialog() == true) try { Request.Text = UsbKey.ReadBounded(dialog.FileName, Protocol.MaxInput); verified = null; } catch (Exception ex) { Status.Text = ex.Message; }
    }
    private void ExportOutput(object sender, RoutedEventArgs e) {
        if (Output.Text.Length == 0) return;
        var dialog = new SaveFileDialog { FileName = "focusguard-response.fgres", Filter = "Публичный протокольный файл|*.fgres;*.json" };
        if (dialog.ShowDialog() == true) try { File.WriteAllText(dialog.FileName, Output.Text); } catch (Exception ex) { Status.Text = ex.Message; }
    }
    private void Show(string text) {
        Output.Text = text;
        try {
            var pixels = new BarcodeWriterPixelData { Format = BarcodeFormat.QR_CODE, Options = new EncodingOptions { Width = 800, Height = 800, Margin = 2 } }.Write(text);
            Qr.Source = BitmapSource.Create(pixels.Width, pixels.Height, 96, 96, PixelFormats.Bgra32, null, pixels.Pixels, pixels.Width * 4);
        } catch (WriterException) { Qr.Source = null; Status.Text = "Данные превышают ёмкость QR. Экспортируйте протокольный файл."; }
    }
}
