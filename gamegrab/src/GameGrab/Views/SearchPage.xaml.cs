using System.Windows.Input;
using GameGrab.ViewModels;
using Wpf.Ui.Controls;

namespace GameGrab.Views;

public partial class SearchPage : Page
{
    public SearchPage()
    {
        InitializeComponent();
        DataContext = new SearchViewModel(App.Host);
    }

    private void SearchBox_KeyDown(object sender, KeyEventArgs e)
    {
        if (e.Key == Key.Enter && DataContext is SearchViewModel vm && vm.SearchCommand.CanExecute(null))
            vm.SearchCommand.Execute(null);
    }

    private void ResultsGrid_DoubleClick(object sender, System.Windows.Input.MouseButtonEventArgs e)
    {
        if (DataContext is SearchViewModel vm && vm.AddSelectedCommand.CanExecute(null))
            vm.AddSelectedCommand.Execute(null);
    }
}
