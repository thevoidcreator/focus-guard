using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using System.Windows.Threading;
using OpenCvSharp;
using ZXing;

namespace FocusGuard.ConsoleApp;
public sealed class CameraWindow : System.Windows.Window
{
    public string Result { get; private set; } = "";
    private readonly VideoCapture capture = new();
    private readonly DispatcherTimer timer = new() { Interval = TimeSpan.FromMilliseconds(150) };
    private readonly Image preview = new();
    private readonly BarcodeReaderGeneric reader = new();
    public CameraWindow() {
        Title = "QR запроса телефона • камера 0"; Width = 720; Height = 580;
        var panel = new StackPanel(); panel.Children.Add(new TextBlock { Text = "Поднесите QR телефона к камере. Закройте окно для отмены." }); panel.Children.Add(preview); Content = panel;
        reader.Options.PossibleFormats = [BarcodeFormat.QR_CODE]; reader.Options.TryHarder = true;
        Loaded += (_, _) => {
            try { if (!capture.Open(0, VideoCaptureAPIs.DSHOW)) throw new InvalidOperationException("Камера недоступна; используйте файл"); timer.Start(); }
            catch (Exception ex) { MessageBox.Show(ex.Message); DialogResult = false; }
        };
        timer.Tick += (_, _) => {
            try {
                using var frame = new Mat(); if (!capture.Read(frame) || frame.Empty() || frame.Channels() != 3) return;
                using var contiguous = frame.Clone(); byte[] bytes = new byte[contiguous.Rows * contiguous.Cols * 3]; Marshal.Copy(contiguous.Data, bytes, 0, bytes.Length);
                preview.Source = BitmapSource.Create(frame.Cols, frame.Rows, 96, 96, PixelFormats.Bgr24, null, bytes, frame.Cols * 3);
                var found = reader.Decode(new RGBLuminanceSource(bytes, frame.Cols, frame.Rows, RGBLuminanceSource.BitmapFormat.BGR24));
                if (found != null && found.Text.Length <= FocusGuard.Core.Protocol.MaxInput) { Result = found.Text; DialogResult = true; }
            } catch (Exception ex) { timer.Stop(); MessageBox.Show("Камера: " + ex.Message); DialogResult = false; }
        };
        Closed += (_, _) => { timer.Stop(); capture.Release(); capture.Dispose(); };
    }
}
