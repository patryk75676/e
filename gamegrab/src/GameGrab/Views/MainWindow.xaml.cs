using System.ComponentModel;
using System.Windows;
using System.Windows.Input;
using GameGrab.ViewModels;
using Wpf.Ui.Controls;

namespace GameGrab.Views;

public partial class MainWindow : FluentWindow
{
    private bool _reallyExit;

    public MainWindow()
    {
        InitializeComponent();
        DataContext = new ShellViewModel(App.Host);

        var settings = App.Host.Settings;
        if ((settings.StartMinimized || App.StartMinimizedFlag) && settings.MinimizeToTray)
        {
            Loaded += (_, _) => Hide();
        }

        Nav.Navigate(typeof(SearchPage));

        // Global shortcuts
        InputBindings.Add(new KeyBinding(new RelayCommand(() => Nav.Navigate(typeof(SearchPage))),
            new KeyGesture(Key.F, ModifierKeys.Control)));
        InputBindings.Add(new KeyBinding(new RelayCommand(() => Nav.Navigate(typeof(DownloadsPage))),
            new KeyGesture(Key.D, ModifierKeys.Control)));
        InputBindings.Add(new KeyBinding(new RelayCommand(() => Nav.Navigate(typeof(LibraryPage))),
            new KeyGesture(Key.L, ModifierKeys.Control)));
        InputBindings.Add(new KeyBinding(new RelayCommand(() => Nav.Navigate(typeof(SettingsPage))),
            new KeyGesture(Key.OemComma, ModifierKeys.Control)));
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

    private void Tray_Exit(object sender, RoutedEventArgs e)
    {
        _reallyExit = true;
        Close();
        Application.Current.Shutdown();
    }

    public void ShowTrayBalloon(string title, string body)
    {
        try { Tray.ShowBalloonTip(title, body, Wpf.Ui.Tray.Controls.BalloonIcon.Info); }
        catch { /* shown with no-op on tray not ready */ }
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
