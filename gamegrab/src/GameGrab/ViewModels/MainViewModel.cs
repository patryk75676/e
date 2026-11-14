using System.Collections.ObjectModel;
using System.Diagnostics;
using System.IO;
using System.Windows;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using GameGrab.Models;
using GameGrab.Services;

namespace GameGrab.ViewModels;

public sealed partial class MainViewModel : ObservableObject
{
    private readonly IServiceHost _host;
    private CancellationTokenSource? _searchCts;

    public ObservableCollection<SearchResult> Results { get; } = new();
    public ObservableCollection<TorrentItem> Transfers { get; }

    [ObservableProperty] private string _query = "";
    [ObservableProperty] private string _status = "ready";
    [ObservableProperty] private bool _searching;
    [ObservableProperty] private SearchResult? _selectedResult;
    [ObservableProperty] private TorrentItem? _selectedTransfer;

    public MainViewModel(IServiceHost host)
    {
        _host = host;
        Transfers = host.Torrents.Items;
    }

    [RelayCommand]
    private async Task SearchAsync()
    {
        if (string.IsNullOrWhiteSpace(Query)) return;
        _searchCts?.Cancel();
        _searchCts = new CancellationTokenSource();
        var ct = _searchCts.Token;

        Results.Clear();
        Searching = true;
        Status = $"searching '{Query}' across {_host.Settings.Sources.Count} sources";

        try
        {
            var collected = new List<SearchResult>();
            await foreach (var r in _host.Search.SearchAsync(Query, _host.Settings, ct))
            {
                collected.Add(r);
            }
            foreach (var r in collected.OrderByDescending(x => x.Score).Take(500))
                Results.Add(r);
            Status = $"{Results.Count} results";
        }
        catch (OperationCanceledException) { Status = "search cancelled"; }
        catch (Exception ex) { Status = $"search error: {ex.Message}"; }
        finally { Searching = false; }
    }

    [RelayCommand]
    private async Task AddSelectedAsync()
    {
        if (SelectedResult is null) return;
        Status = $"resolving '{SelectedResult.Title}'";
        try
        {
            var resolved = await _host.Search.ResolveAsync(SelectedResult, _host.Settings, CancellationToken.None);
            if (!string.IsNullOrEmpty(resolved.Magnet))
            {
                await _host.Torrents.AddMagnetAsync(resolved.Magnet, resolved.Title);
                Status = $"queued magnet: {resolved.Title}";
            }
            else if (!string.IsNullOrEmpty(resolved.TorrentUrl))
            {
                await _host.Torrents.AddTorrentUrlAsync(resolved.TorrentUrl, CancellationToken.None);
                Status = $"queued torrent: {resolved.Title}";
            }
            else if (resolved.DirectUrls.Count > 0)
            {
                Status = $"HTTP mirrors found ({resolved.DirectUrls.Count}) — opening";
                foreach (var url in resolved.DirectUrls.Take(5))
                    OpenBrowser(url);
            }
            else
            {
                Status = "no downloadable link found";
            }
        }
        catch (Exception ex) { Status = $"resolve failed: {ex.Message}"; }
    }

    [RelayCommand]
    private async Task PauseAsync()
    {
        if (SelectedTransfer is not null) await _host.Torrents.PauseAsync(SelectedTransfer);
    }

    [RelayCommand]
    private async Task ResumeAsync()
    {
        if (SelectedTransfer is not null) await _host.Torrents.ResumeAsync(SelectedTransfer);
    }

    [RelayCommand]
    private async Task RemoveAsync()
    {
        if (SelectedTransfer is null) return;
        var ans = MessageBox.Show("Delete files on disk too?", "GameGrab",
            MessageBoxButton.YesNoCancel, MessageBoxImage.Question);
        if (ans == MessageBoxResult.Cancel) return;
        await _host.Torrents.RemoveAsync(SelectedTransfer, ans == MessageBoxResult.Yes);
    }

    [RelayCommand]
    private void OpenFolder()
    {
        var target = SelectedTransfer?.SavePath ?? _host.Settings.DownloadDir;
        if (!Directory.Exists(target)) Directory.CreateDirectory(target);
        Process.Start(new ProcessStartInfo("explorer.exe", target) { UseShellExecute = true });
    }

    [RelayCommand]
    private async Task PasteMagnetAsync()
    {
        try
        {
            var text = Clipboard.GetText();
            if (string.IsNullOrWhiteSpace(text)) return;
            if (text.StartsWith("magnet:"))
            {
                await _host.Torrents.AddMagnetAsync(text);
                Status = "magnet added from clipboard";
            }
            else if (text.StartsWith("http") && text.EndsWith(".torrent"))
            {
                await _host.Torrents.AddTorrentUrlAsync(text, CancellationToken.None);
                Status = "torrent url added from clipboard";
            }
            else Status = "clipboard did not contain a magnet or .torrent url";
        }
        catch (Exception ex) { Status = $"clipboard: {ex.Message}"; }
    }

    [RelayCommand]
    private async Task AddTorrentFileAsync()
    {
        var dlg = new Microsoft.Win32.OpenFileDialog
        {
            Filter = "Torrent files (*.torrent)|*.torrent|All files|*.*",
            Multiselect = true,
        };
        if (dlg.ShowDialog() != true) return;
        foreach (var f in dlg.FileNames)
        {
            try { await _host.Torrents.AddTorrentFileAsync(f); }
            catch (Exception ex) { Status = $"failed {f}: {ex.Message}"; }
        }
    }

    private static void OpenBrowser(string url)
    {
        try { Process.Start(new ProcessStartInfo(url) { UseShellExecute = true }); }
        catch { }
    }
}
