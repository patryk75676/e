using System.Diagnostics;
using System.IO;
using System.Windows;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using GameGrab.Models;
using GameGrab.Services;

namespace GameGrab.ViewModels;

public sealed partial class DownloadsViewModel : ObservableObject
{
    private readonly IServiceHost _host;
    public IEnumerable<TorrentItem> Items => _host.Torrents.Items;

    [ObservableProperty] private TorrentItem? _selected;

    public DownloadsViewModel(IServiceHost host) => _host = host;

    [RelayCommand] private async Task PauseAsync()   { if (Selected is not null) await _host.Torrents.PauseAsync(Selected); }
    [RelayCommand] private async Task ResumeAsync()  { if (Selected is not null) await _host.Torrents.ResumeAsync(Selected); }
    [RelayCommand] private async Task RecheckAsync() { if (Selected is not null) await _host.Torrents.ForceRecheckAsync(Selected); }

    [RelayCommand]
    private async Task RemoveAsync()
    {
        if (Selected is null) return;
        var ans = MessageBox.Show("Delete files on disk too?", "GameGrab",
            MessageBoxButton.YesNoCancel, MessageBoxImage.Question);
        if (ans == MessageBoxResult.Cancel) return;
        await _host.Torrents.RemoveAsync(Selected, ans == MessageBoxResult.Yes);
    }

    [RelayCommand]
    private void OpenFolder()
    {
        var path = Selected?.SavePath ?? _host.Settings.DownloadDir;
        if (!Directory.Exists(path)) Directory.CreateDirectory(path);
        Process.Start(new ProcessStartInfo("explorer.exe", path) { UseShellExecute = true });
    }

    [RelayCommand]
    private void CopyMagnet()
    {
        if (Selected is null) return;
        var m = _host.Torrents.GetMagnet(Selected);
        if (!string.IsNullOrEmpty(m)) Clipboard.SetText(m);
    }

    [RelayCommand]
    private async Task PauseAllAsync()
    {
        foreach (var it in Items.ToList()) await _host.Torrents.PauseAsync(it);
    }

    [RelayCommand]
    private async Task ResumeAllAsync()
    {
        foreach (var it in Items.ToList()) await _host.Torrents.ResumeAsync(it);
    }
}
