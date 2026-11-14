using System.Windows.Controls;
using GameGrab.ViewModels;

namespace GameGrab.Views;

public partial class DownloadsPage : Page
{
    public DownloadsPage()
    {
        InitializeComponent();
        DataContext = new DownloadsViewModel(App.Host);
    }
}
