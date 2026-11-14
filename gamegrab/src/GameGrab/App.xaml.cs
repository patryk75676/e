using System.Text;
using System.Windows;
using System.Windows.Threading;
using GameGrab.Services;

namespace GameGrab;

public partial class App : Application
{
    public static IServiceHost Host { get; private set; } = null!;

    public static bool StartMinimizedFlag { get; private set; }

    protected override void OnStartup(StartupEventArgs e)
    {
        base.OnStartup(e);
        Encoding.RegisterProvider(CodePagesEncodingProvider.Instance);
        StartMinimizedFlag = e.Args.Any(a => string.Equals(a, "--minimized", StringComparison.OrdinalIgnoreCase));
        Host = new ServiceHost();
        Host.Start();
        try { GameGrab.ViewModels.ThemeApplier.Apply(Host.Settings.Theme); } catch { }
        DispatcherUnhandledException += OnUiException;
    }

    protected override void OnExit(ExitEventArgs e)
    {
        try { Host?.StopAsync().GetAwaiter().GetResult(); } catch { }
        base.OnExit(e);
    }

    private void OnUiException(object sender, DispatcherUnhandledExceptionEventArgs e)
    {
        MessageBox.Show(e.Exception.ToString(), "GameGrab", MessageBoxButton.OK, MessageBoxImage.Error);
        e.Handled = true;
    }
}
