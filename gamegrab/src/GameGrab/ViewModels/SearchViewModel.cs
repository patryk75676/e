using System.Collections.ObjectModel;
using System.Diagnostics;
using System.Windows;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using GameGrab.Models;
using GameGrab.Services;

namespace GameGrab.ViewModels;

public sealed partial class SearchViewModel : ObservableObject
{
    private readonly IServiceHost _host;
    private CancellationTokenSource? _cts;
    private readonly List<SearchResult> _all = new();

    public ObservableCollection<SearchResult> Results { get; } = new();
    public ObservableCollection<SourceToggle> Sources { get; } = new();

    [ObservableProperty] private string _query = "";
    [ObservableProperty] private string _status = "ready";
    [ObservableProperty] private bool _searching;
    [ObservableProperty] private SearchResult? _selected;
    [ObservableProperty] private bool _onlyWithSeeds;
    [ObservableProperty] private string _filter = "";

    public SearchViewModel(IServiceHost host)
    {
        _host = host;
        foreach (var name in _host.Search.Available)
            Sources.Add(new SourceToggle(name, _host.Settings.Sources.Contains(name), OnSourceToggle));
    }

    private void OnSourceToggle(SourceToggle t)
    {
        var list = _host.Settings.Sources;
        if (t.Enabled && !list.Contains(t.Name)) list.Add(t.Name);
        else if (!t.Enabled) list.Remove(t.Name);
        _host.Settings.Save();
    }

    partial void OnOnlyWithSeedsChanged(bool value) => ApplyFilter();
    partial void OnFilterChanged(string value) => ApplyFilter();

    [RelayCommand]
    private async Task SearchAsync()
    {
        if (string.IsNullOrWhiteSpace(Query)) return;
        _cts?.Cancel();
        _cts = new CancellationTokenSource();
        var ct = _cts.Token;

        Results.Clear();
        _all.Clear();
        Searching = true;
        Status = $"searching '{Query}' across {_host.Settings.Sources.Count} sources";

        try
        {
            await foreach (var r in _host.Search.SearchAsync(Query, _host.Settings, ct))
            {
                _all.Add(r);
                InsertRanked(r);
            }
            Status = $"{Results.Count} results";
        }
        catch (OperationCanceledException) { Status = "search cancelled"; }
        catch (Exception ex) { Status = $"search error: {ex.Message}"; }
        finally { Searching = false; }
    }

    private void InsertRanked(SearchResult r)
    {
        if (!Passes(r)) return;
        int idx = 0;
        while (idx < Results.Count && Results[idx].Score >= r.Score) idx++;
        Results.Insert(idx, r);
    }

    private bool Passes(SearchResult r)
    {
        if (OnlyWithSeeds && r.Seeders <= 0 && r.Kind != ReleaseKind.DirectHttp) return false;
        if (!string.IsNullOrWhiteSpace(Filter) &&
            !r.Title.Contains(Filter, StringComparison.OrdinalIgnoreCase)) return false;
        return true;
    }

    private void ApplyFilter()
    {
        Results.Clear();
        foreach (var r in _all.OrderByDescending(x => x.Score))
            if (Passes(r)) Results.Add(r);
        Status = $"{Results.Count} results";
    }

    [RelayCommand]
    private async Task AddSelectedAsync()
    {
        if (Selected is null) return;
        var sel = Selected;
        Status = $"resolving '{sel.Title}'";
        try
        {
            var resolved = await _host.Search.ResolveAsync(sel, _host.Settings, CancellationToken.None);
            if (!string.IsNullOrEmpty(resolved.Magnet))
            {
                await _host.Torrents.AddMagnetAsync(resolved.Magnet, resolved.Title);
                Status = $"queued: {resolved.Title}";
            }
            else if (!string.IsNullOrEmpty(resolved.TorrentUrl))
            {
                await _host.Torrents.AddTorrentUrlAsync(resolved.TorrentUrl, CancellationToken.None);
                Status = $"queued: {resolved.Title}";
            }
            else if (resolved.DirectUrls.Count > 0)
            {
                Status = $"opening {resolved.DirectUrls.Count} HTTP mirror(s)";
                foreach (var url in resolved.DirectUrls.Take(5)) OpenBrowser(url);
            }
            else Status = "no downloadable link found";
        }
        catch (Exception ex) { Status = $"resolve failed: {ex.Message}"; }
    }

    [RelayCommand]
    private void CopyMagnet()
    {
        if (Selected is null) return;
        _ = _host.Search.ResolveAsync(Selected, _host.Settings, CancellationToken.None)
            .ContinueWith(t =>
            {
                var m = t.Result.Magnet;
                if (!string.IsNullOrEmpty(m))
                    Application.Current.Dispatcher.Invoke(() => Clipboard.SetText(m));
            });
    }

    [RelayCommand]
    private void OpenPage()
    {
        if (Selected is null) return;
        OpenBrowser(Selected.DetailUrl);
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
            else if (text.StartsWith("http"))
            {
                await _host.Torrents.AddTorrentUrlAsync(text, CancellationToken.None);
                Status = "torrent url added from clipboard";
            }
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

public sealed partial class SourceToggle : ObservableObject
{
    private readonly Action<SourceToggle> _onChanged;
    public string Name { get; }
    [ObservableProperty] private bool _enabled;

    public SourceToggle(string name, bool enabled, Action<SourceToggle> onChanged)
    {
        Name = name;
        _enabled = enabled;
        _onChanged = onChanged;
    }

    partial void OnEnabledChanged(bool value) => _onChanged(this);
}
