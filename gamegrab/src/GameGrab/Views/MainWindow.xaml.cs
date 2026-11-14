using System.ComponentModel;
using System.Windows;
using System.Windows.Input;
using GameGrab.ViewModels;
using Wpf.Ui.Controls;

namespace GameGrab.Views;

public partial class MainWindow : Wpf.Ui.Controls.FluentWindow
{
    private bool _reallyExit;

    public MainWindow()
    {
        InitializeComponent();
        DataContext = new ShellViewModel(App.Host);

        var settings = App.Host.Settings;
        var startHidden = (settings.StartMinimized || App.StartMinimizedFlag) && settings.MinimizeToTray;

        // Defer NavigationView.Navigate: WPF-UI 3.0.5 throws NRE when Navigate is
        // called before the control template is applied.
        Loaded += (_, _) =>
        {
            try { Nav.Navigate(typeof(SearchPage)); } catch { }
            if (startHidden) Hide();
        };

        // Global shortcuts
        InputBindings.Add(new KeyBinding(new RelayCommand(() => SafeNavigate(typeof(SearchPage))),
            new KeyGesture(Key.F, ModifierKeys.Control)));
        InputBindings.Add(new KeyBinding(new RelayCommand(() => SafeNavigate(typeof(DownloadsPage))),
            new KeyGesture(Key.D, ModifierKeys.Control)));
        InputBindings.Add(new KeyBinding(new RelayCommand(() => SafeNavigate(typeof(LibraryPage))),
            new KeyGesture(Key.L, ModifierKeys.Control)));
        InputBindings.Add(new KeyBinding(new RelayCommand(() => SafeNavigate(typeof(SettingsPage))),
            new KeyGesture(Key.OemComma, ModifierKeys.Control)));
    }

    private void SafeNavigate(Type pageType)
    {
        try { Nav.Navigate(pageType); } catch { }
    }

    private void Window_StateChanged(object? sender, EventArgs e)
    {
        if (WindowState == WindowState.Minimized && App.Host.Settings.MinimizeToTray)
            Hide();
    }

    private void Window_Closing(object? sender, CancelEventArgs e)
    {
        if (_reallyExit || !App.Host.Settings.MinimizeToTray) return;
        e.Cancel = true;
        Hide();
    }

    private void Tray_Show(object sender, RoutedEventArgs e)
    {
        Show();
        if (WindowState == WindowState.Minimized) WindowState = WindowState.Normal;
        Activate();
    }

    // Exit is driven from the window's own close chrome; the tray exposes show-only.
    internal void ReallyExit()
    {
        _reallyExit = true;
        Close();
        Application.Current.Shutdown();
    }

    public void ShowTrayBalloon(string title, string body)
    {
        // WPF-UI Tray exposes a NotifyIcon wrapper; the balloon API varies by version,
        // so only a tooltip refresh is done here. Toast surface is handled via status bar.
        try { Tray.TooltipText = $"{title}: {body}"; } catch { }
    }
}

internal sealed class RelayCommand : ICommand
{
    private readonly Action _action;
    public RelayCommand(Action action) => _action = action;
    public bool CanExecute(object? parameter) => true;
    public void Execute(object? parameter) => _action();
    public event EventHandler? CanExecuteChanged { add { } remove { } }
}
