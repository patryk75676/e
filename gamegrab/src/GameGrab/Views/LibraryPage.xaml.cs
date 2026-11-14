using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using GameGrab.Models;
using GameGrab.ViewModels;

namespace GameGrab.Views;

public partial class LibraryPage : Page
{
    public LibraryPage()
    {
        InitializeComponent();
        DataContext = new LibraryViewModel(App.Host);
    }

    private void Search_KeyDown(object sender, KeyEventArgs e)
    {
        if (e.Key == Key.Enter && DataContext is LibraryViewModel vm && vm.SearchCommand.CanExecute(null))
            vm.SearchCommand.Execute(null);
    }

    private void Card_Click(object sender, MouseButtonEventArgs e)
    {
        if (sender is FrameworkElement fe && fe.Tag is SteamApp app && DataContext is LibraryViewModel vm)
        {
            vm.Selected = app;
            if (vm.AddSelectedCommand.CanExecute(null))
                vm.AddSelectedCommand.Execute(null);
        }
    }
}
