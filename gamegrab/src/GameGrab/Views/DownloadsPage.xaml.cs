using GameGrab.ViewModels;
using Wpf.Ui.Controls;

namespace GameGrab.Views;

public partial class DownloadsPage : Page
{
    public DownloadsPage()
    {
        InitializeComponent();
        DataContext = new DownloadsViewModel(App.Host);
    }
}
