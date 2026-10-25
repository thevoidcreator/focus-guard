using System.IO;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Text.Json;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using FocusGuard.Core;
using Microsoft.Win32;
namespace FocusGuard.ConsoleApp;
public partial class MainWindow : Window {
    private string route = "", root = "", folder = "", request = "", response = "", recovery = "replace";
    private int step;
    private bool dark = true, busy;
    private PasswordBox password = new(), repeat = new();
    private TextBox input = new();
    private CheckBox confirm = new();
    private readonly string settings = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "FocusGuard");
    public MainWindow() {
        InitializeComponent();
        try { dark = JsonSerializer.Deserialize<bool>(File.ReadAllText(Path.Combine(settings,"dark-theme.json"))); } catch { }
        ApplyTheme(); Render();
    }
    private void ApplyTheme() {
        string[] keys=["Bg","Panel","Ink","Muted","Accent","Line","ButtonInk"];
        string[] colors=dark ? ["#14221F","#1B2E27","#EDF6F1","#B0C7BC","#79D5B5","#344D45","#102E22"] : ["#FFFFFF","#F0F7F3","#20372D","#5D7468","#126E5F","#CCDED3","#FFFFFF"];
        for(int i=0;i<keys.Length;i++) Application.Current.Resources[keys[i]]=new SolidColorBrush((Color)ColorConverter.ConvertFromString(colors[i]));
        Theme.Content=dark?"☀ Светлая тема":"☾ Тёмная тема";
    }
    private void ToggleTheme(object sender,RoutedEventArgs e) { dark=!dark; ApplyTheme(); try { Directory.CreateDirectory(settings);File.WriteAllText(Path.Combine(settings,"dark-theme.json"),JsonSerializer.Serialize(dark)); }catch(Exception ex){Status.Text="Не удалось сохранить тему: "+ex.Message;} }
    private void Text(string text,bool muted=false) { var t=new TextBlock{Text=text};if(muted)t.SetResourceReference(ForegroundProperty,"Muted");Body.Children.Add(t); }
    private void Button(string label,Action click) { var b=new Button{Content=label,HorizontalAlignment=HorizontalAlignment.Left}; b.Click+=(_,_)=>{if(!busy)Try(click);};Body.Children.Add(b); }
    private void Try(Action action) { try { action(); }catch(Exception ex){Status.Text=ex.Message;} }
    private int Last => route=="setup"?4:3;
    private void Start(string mode) { route=mode;step=0;request="";response="";Status.Text="";Render(); }
    private void Go(int n) { step=n;Status.Text="";Render(); }
    private void Back(object sender,RoutedEventArgs e) { if(busy)return;if(step>0)Go(step-1);else {route="";Render();} }
    private void Render() {
        Body.Children.Clear();NextButton.Visibility=route==""?Visibility.Collapsed:Visibility.Visible;BackButton.Visibility=NextButton.Visibility;
        if(route=="") {
            Progress.Text="Восстановление доступа";Heading.Text="Что вы хотите сделать?";Description.Text="Выберите задачу. Дальше — один шаг на экране.";
            var cards=new Grid();cards.ColumnDefinitions.Add(new ColumnDefinition());cards.ColumnDefinitions.Add(new ColumnDefinition());
            void Card(int column,string symbol,string title,string subtitle,string mode){
                var content=new StackPanel();var icon=new TextBlock{Text=symbol,FontSize=32};icon.SetResourceReference(ForegroundProperty,"Accent");content.Children.Add(icon);
                content.Children.Add(new TextBlock{Text=title,FontSize=21,FontWeight=FontWeights.SemiBold,Margin=new Thickness(0,12,0,10)});
                var caption=new TextBlock{Text=subtitle,FontSize=14};caption.SetResourceReference(ForegroundProperty,"Muted");content.Children.Add(caption);
                var card=new Button{Content=content,MinHeight=210,Padding=new Thickness(25),HorizontalContentAlignment=HorizontalAlignment.Stretch};card.Click+=(_,_)=>Start(mode);Grid.SetColumn(card,column);cards.Children.Add(card);
            }
            Card(0,"▣","Подготовить флешку","Первое подключение новой или существующей флешки к телефону.","setup");
            Card(1,"↻","Восстановить доступ","Заменить доверенного человека или разрешить удаление после 48 часов.","recovery");Body.Children.Add(cards);return;
        }
        string[] stages=route=="setup"?["Флешка","Пароль","Приглашение телефону","Запрос телефона","Ответ телефону"]:["Что восстановить","Флешка и пароль","Запрос телефона","Ответ телефону"];
        Progress.Text=$"Шаг {step+1} из {stages.Length} · {stages[step]}";
        NextButton.Content="Продолжить";
        if(route=="recovery"&&step==0) {
            Heading.Text="Что нужно восстановить?";Description.Text="Доверенный человек для этого пути не требуется.";
            var a=new RadioButton{Content="Заменить доверенного человека",IsChecked=recovery=="replace",Margin=new Thickness(0,16,0,16)};a.Checked+=(_,_)=>recovery="replace";Body.Children.Add(a);
            var b=new RadioButton{Content="Снять управление и разрешить удаление Focus Guard",IsChecked=recovery=="remove",Margin=new Thickness(0,16,0,16)};b.Checked+=(_,_)=>recovery="remove";Body.Children.Add(b);
            Text("На телефоне начните выбранную процедуру. Через 48 часов создайте запрос завершения. Программа принимает оба этапа, а ожидание проверяет телефон.",true);return;
        }
        if(route=="setup"&&step==0||route=="recovery"&&step==1) {
            Heading.Text="Подключите свою флешку";Description.Text="Форматировать носитель не нужно. Другие файлы сохраняются.";
            var drives=DriveInfo.GetDrives().Where(d=>d.IsReady&&d.DriveType==DriveType.Removable).ToArray();
            if(drives.Length==0)Text("Подключённых съёмных флешек не найдено.");
            foreach(var d in drives){var disk=d;var r=new RadioButton{Content=$"{d.VolumeLabel} · {d.Name} · {d.TotalSize/1073741824.0:F1} ГБ",Margin=new Thickness(0,12,0,12),IsChecked=d.Name==root||root==""&&d==drives[0]};if(r.IsChecked==true)root=d.Name;r.Checked+=(_,_)=>{root=disk.Name;folder="";};Body.Children.Add(r);}
            Button("Обновить список",Render);
            if(route=="recovery"){Text("Введите пароль зарегистрированной флешки.");password=new PasswordBox();Body.Children.Add(password);}
            return;
        }
        if(route=="setup"&&step==1) {
            Heading.Text=folder==""?"Придумайте пароль флешки":"Введите пароль вашей флешки";Description.Text=folder==""?"Не менее 12 символов. Храните пароль отдельно от флешки.":"Ключ уже найден. Создавать новый не требуется.";
            Text("Пароль");password=new PasswordBox();Body.Children.Add(password);
            if(folder==""){Text("Повторите пароль");repeat=new PasswordBox();Body.Children.Add(repeat);}
            NextButton.Content=folder==""?"Создать и проверить ключ":"Проверить флешку";return;
        }
        if(route=="setup"&&step==2){
            Heading.Text="Передайте приглашение телефону";Description.Text="Ключ создан и проверен. Теперь свяжем его с Focus Guard.";
            Button("Скопировать приглашение",()=>{Clipboard.SetText(JsonSerializer.Serialize(UsbKey.ReadMetadata(folder),Protocol.Json));Status.Text="Приглашение скопировано. Отправьте текст себе в мессенджере.";});
            Text("На телефоне: скопируйте полученный текст → Focus Guard → «Вставить код» → «Создать запрос регистрации флешки». Затем скопируйте запрос и отправьте его обратно на ПК.");
            NextButton.Content="Дальше: запрос с телефона";return;
        }
        if(step==Last) {
            Heading.Text="Передайте ответ телефону";Description.Text="Ответ подготовлен. Регистрация или восстановление ещё должны быть подтверждены телефоном.";
            Button("Скопировать ответ",()=>{Clipboard.SetText(response);Status.Text="Ответ скопирован. Отправьте текст на телефон через мессенджер.";});
            Button("Сохранить ответ файлом",()=>Save(response));
            Text("На телефоне: скопируйте ответ → Focus Guard → «Вставить код» → «Применить подтверждение».");
            Text(route=="setup"?"Дождитесь статуса «Флешка восстановления: настроена». Только тогда подключение закончено.":"Телефон покажет начало ожидания или результат завершения. Часы ПК не сокращают 48 часов.",true);
            NextButton.Content="К началу";return;
        }
        Heading.Text="Вставьте запрос с телефона";Description.Text="Скопируйте полученный текст в мессенджере и нажмите кнопку ниже.";
        input=new TextBox{Text="",Height=90,TextWrapping=TextWrapping.Wrap,VerticalScrollBarVisibility=ScrollBarVisibility.Auto};
        // The visible input holds the transferable envelope, never private key material.
        input.Text=request==""?"":JsonSerializer.Serialize(new ApprovalFile(2,request),Protocol.Json);
        Body.Children.Add(input);Button("Вставить и проверить запрос",()=>Inspect(Clipboard.GetText()));
        Button("Открыть запрос файлом",()=>{var d=new OpenFileDialog{Filter="Все файлы|*.*"};if(d.ShowDialog()==true)Inspect(UsbKey.ReadBounded(d.FileName,Protocol.MaxInput));});
        if(request!="")Text(CompanionProtocol.Label(CompanionProtocol.Inspect(request).Action));
        confirm=new CheckBox{Content="Сверил телефон и действие. Подтверждаю именно этот запрос."};Body.Children.Add(confirm);
        Text("Введите пароль флешки заново для подписи.");password=new PasswordBox();Body.Children.Add(password);NextButton.Content="Подтвердить флешкой";
    }
    private void FindKey() {
        if(!UsbKey.RemovablePresent(root))throw new IOException("Выберите подключённую флешку");
        string parent=Path.Combine(root,UsbKey.Folder);var keys=Directory.Exists(parent)?Directory.GetDirectories(parent).Where(p=>File.Exists(Path.Combine(p,"public.json"))&&UsbKey.ReadMetadata(p).Role=="main").ToArray():[];
        if(keys.Length>1)throw new InvalidOperationException("На носителе несколько ключей. Для исключения ошибки используйте флешку с одним нужным main-ключом. Не удаляйте ключи без резервной копии.");
        folder=keys.FirstOrDefault()??"";
    }
    private static char[] ReadPassword(PasswordBox box) {using var s=box.SecurePassword;IntPtr p=Marshal.SecureStringToBSTR(s);try{var a=new char[s.Length];Marshal.Copy(p,a,0,a.Length);return a;}finally{Marshal.ZeroFreeBSTR(p);box.Clear();}}
    private async Task Run(Func<char[],string> operation,Action<string> done) {
        busy=true;Body.IsEnabled=false;NextButton.IsEnabled=false;BackButton.IsEnabled=false;var pass=ReadPassword(password);
        try{Status.Text="Проверяем флешку…";string result=await Task.Run(()=>operation(pass));done(result);}catch(Exception ex){Status.Text="Не выполнено: "+ex.Message;}
        finally{Array.Clear(pass);busy=false;Body.IsEnabled=true;NextButton.IsEnabled=true;BackButton.IsEnabled=true;}
    }
    private async void Next(object sender,RoutedEventArgs e) {
        if(busy)return;
        try {
            if(step==Last){route="";request="";response="";Render();return;}
            if(route=="recovery"&&step==0){Go(1);return;}
            if(route=="setup"&&step==0){FindKey();Go(1);return;}
            if(route=="setup"&&step==2){Go(3);return;}
            if(route=="setup"&&step==1||route=="recovery"&&step==1){
                if(route=="recovery"){FindKey();if(folder=="")throw new InvalidOperationException("На этой флешке нет ключа восстановления. Подключите зарегистрированный носитель.");}
                string selected=folder,disk=root;char[] repeated=selected==""?ReadPassword(repeat):[];
                try{await Run(pass=>{
                    if(selected==""){if(!pass.AsSpan().SequenceEqual(repeated))throw new ArgumentException("Пароли не совпадают");return UsbKey.Create(disk,pass,"main");}
                    if(!UsbKey.RemovablePresent(disk))throw new IOException("Флешка отключена");
                    using var key=UsbKey.Open(selected,pass);var data=RandomNumberGenerator.GetBytes(32);var sig=key.SignData(data,HashAlgorithmName.SHA256);using var pub=Protocol.PublicKey(UsbKey.ReadMetadata(selected).PublicSpki);
                    if(!pub.VerifyData(data,sig,HashAlgorithmName.SHA256)||!UsbKey.RemovablePresent(disk))throw new CryptographicException("Проверка флешки не прошла");return selected;
                },result=>{folder=result;Go(2);});}finally{Array.Clear(repeated);}return;
            }
            if(request=="" && !string.IsNullOrWhiteSpace(input.Text)){Inspect(input.Text);return;}
            if(request==""||confirm.IsChecked!=true)throw new InvalidOperationException("Вставьте и проверьте запрос, затем подтвердите действие галочкой.");
            string token=request,selectedFolder=folder;
            var parsed=CompanionProtocol.ReadFile(input.Text.Trim());if(parsed!=request)throw new InvalidOperationException("Текст изменён. Вставьте и проверьте запрос заново.");
            await Run(pass=>CompanionProtocol.Respond(token,selectedFolder,pass),result=>{response=result;Go(Last);});
        }catch(Exception ex){Status.Text=ex.Message;}
    }
    private void Inspect(string text) {
        request="";confirm.IsChecked=false;
        string token=CompanionProtocol.ReadFile(text.Trim());var m=CompanionProtocol.Inspect(token);CompanionProtocol.Verify(token,m.PhoneSpki);
        _=CompanionProtocol.Label(m.Action);
        if(m.Phase!="request"||m.Digest!=""||m.SignerSpki!=UsbKey.ReadMetadata(folder).PublicSpki)throw new InvalidOperationException("Запрос не для этой флешки");
        if(route=="setup"&&m.Action!="usb_pair"||route=="recovery"&&!(m.Action=="usb_start_"+recovery||m.Action=="usb_finish_"+recovery))throw new InvalidOperationException("Запрос относится к другой задаче. Выберите нужный сценарий.");
        string registry=Path.Combine(settings,"phones-v2.json");var pins=File.Exists(registry)?JsonSerializer.Deserialize<Dictionary<string,string>>(UsbKey.ReadBounded(registry,Protocol.MaxInput),Protocol.Json)??throw new FormatException("Реестр повреждён"):new();
        if(pins.TryGetValue(m.PhoneId,out var known)&&known!=m.PhoneSpki)throw new InvalidOperationException("Идентичность телефона изменилась");
        if(!pins.ContainsKey(m.PhoneId)){
            if(MessageBox.Show($"Сверьте внизу экрана телефона:\nТелефон: {m.PhoneId}\nИдентичность: {Protocol.KeyId(m.PhoneSpki)}\nСовпадает?","Первое подключение",MessageBoxButton.YesNo)!=MessageBoxResult.Yes)return;
            pins[m.PhoneId]=m.PhoneSpki;Directory.CreateDirectory(settings);File.WriteAllText(registry+".tmp",JsonSerializer.Serialize(pins,Protocol.Json));File.Move(registry+".tmp",registry,true);
        }
        request=token;Render();Status.Text="Подпись проверена. Проверьте действие, отметьте галочку и введите пароль флешки.";
    }
    private void Save(string text){var d=new SaveFileDialog{FileName="FocusGuard-response.fg2.json",Filter="Документ Focus Guard|*.json"};if(d.ShowDialog()==true){File.WriteAllText(d.FileName,text);Status.Text="Ответ сохранён. Передайте его телефону.";}}
}
