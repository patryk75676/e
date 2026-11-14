using System.Windows;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using GameGrab.Services;

namespace GameGrab.ViewModels;

public sealed partial class SettingsViewModel : ObservableObject
{
    private readonly IServiceHost _host;
    public Settings S => _host.Settings;

    public IReadOnlyList<string> Themes { get; } = new[] { "Dark", "Light" };
    public IReadOnlyList<string> EncryptionModes { get; } = new[] { "Forced", "Enabled", "Disabled" };

    [ObservableProperty] private string _status = "";

    public SettingsViewModel(IServiceHost host) => _host = host;

    [RelayCommand]
    private void BrowseDownloads()
    {
        var dlg = new Microsoft.Win32.OpenFolderDialog
        {
            InitialDirectory = S.DownloadDir,
            Title = "Choose download folder"
        };
        if (dlg.ShowDialog() == true)
        {
            S.DownloadDir = dlg.FolderName;
            OnPropertyChanged(nameof(S));
            Save();
        }
    }

    [RelayCommand]
    private void Save()
    {
        S.Save();
        ThemeApplier.Apply(S.Theme);
        Status = "saved";
    }

    [RelayCommand]
    private void OpenConfigFolder()
    {
        try
        {
            System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo("explorer.exe", Settings.Root)
            {
                UseShellExecute = true
            });
        }
        catch (Exception ex) { Status = ex.Message; }
    }
}

public static class ThemeApplier
{
    public static void Apply(string theme)
    {
        var mode = theme == "Light" ? Wpf.Ui.Appearance.ApplicationTheme.Light
                                    : Wpf.Ui.Appearance.ApplicationTheme.Dark;
        try { Wpf.Ui.Appearance.ApplicationThemeManager.Apply(mode); } catch { }
    }
}
