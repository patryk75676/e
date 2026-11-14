using System.Windows.Controls;
using GameGrab.Services;
using GameGrab.ViewModels;

namespace GameGrab.Views;

public partial class SettingsPage : Page
{
    public SettingsPage()
    {
        InitializeComponent();
        var vm = new SettingsViewModel(App.Host);
        DataContext = vm;
        PwdBox.Password = vm.S.RuTrackerPass;
    }

    private void Pwd_Changed(object sender, System.Windows.RoutedEventArgs e)
    {
        if (sender is PasswordBox p && p.Tag is Settings s)
            s.RuTrackerPass = p.Password;
    }
}
