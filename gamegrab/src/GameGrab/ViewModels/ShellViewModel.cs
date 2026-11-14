using CommunityToolkit.Mvvm.ComponentModel;
using GameGrab.Services;

namespace GameGrab.ViewModels;

public sealed partial class ShellViewModel : ObservableObject
{
    private readonly IServiceHost _host;

    [ObservableProperty] private string _status = "ready";
    [ObservableProperty] private string _globalRates = "↓ 0 KB/s   ↑ 0 KB/s";
    [ObservableProperty] private int _activeCount;

    public ShellViewModel(IServiceHost host)
    {
        _host = host;
        _host.Torrents.RatesUpdated += OnRates;
        _host.Torrents.Items.CollectionChanged += (_, _) => ActiveCount = _host.Torrents.Items.Count;
        _host.Torrents.TorrentCompleted += t =>
        {
            Status = $"finished: {t.Name}";
            if (_host.Settings.ToastOnComplete)
                Toast.Show("GameGrab", $"{t.Name} finished");
        };
    }

    private void OnRates(long down, long up)
    {
        GlobalRates = $"↓ {Fmt(down)}   ↑ {Fmt(up)}";
    }

    private static string Fmt(long b)
    {
        string[] u = { "B/s", "KB/s", "MB/s", "GB/s" };
        double f = b;
        int i = 0;
        while (f >= 1024 && i < u.Length - 1) { f /= 1024; i++; }
        return $"{f:0.#} {u[i]}";
    }
}
