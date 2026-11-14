using System.Windows.Input;
using GameGrab.ViewModels;
using Wpf.Ui.Controls;

namespace GameGrab.Views;

public partial class MainWindow : FluentWindow
{
    public MainWindow()
    {
        InitializeComponent();
        DataContext = new MainViewModel(App.Host);
    }

    private void SearchBox_KeyDown(object sender, KeyEventArgs e)
    {
        if (e.Key == Key.Enter && DataContext is MainViewModel vm && vm.SearchCommand.CanExecute(null))
            vm.SearchCommand.Execute(null);
    }

    private void ResultsGrid_DoubleClick(object sender, MouseButtonEventArgs e)
    {
        if (DataContext is MainViewModel vm && vm.AddSelectedCommand.CanExecute(null))
            vm.AddSelectedCommand.Execute(null);
    }
}
