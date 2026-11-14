using System.IO;
using System.Runtime.InteropServices;
using System.Text;
using System.Windows;

namespace GameGrab.Services;

public static class Toast
{
    // Simple balloon toast via tray icon is handled by WPF-UI NotifyIcon when available.
    // As a dependency-free fallback, we schedule a Dispatcher Invoke to show a tray balloon.
    public static void Show(string title, string body)
    {
        try
        {
            Application.Current.Dispatcher.Invoke(() =>
            {
                if (Application.Current.MainWindow is Views.MainWindow mw)
                    mw.ShowTrayBalloon(title, body);
            });
        }
        catch { }
    }
}
