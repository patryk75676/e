using System.Collections.ObjectModel;
using System.IO;
using System.Net.Http;
using System.Windows;
using MonoTorrent;
using MonoTorrent.Client;
using GameGrab.Models;

namespace GameGrab.Services;

public sealed class TorrentEngine : IAsyncDisposable
{
    private readonly ClientEngine _engine;
    private readonly HttpClient _http;
    private readonly Settings _settings;
    private readonly Dictionary<TorrentManager, TorrentItem> _map = new();
    private readonly Timer _poll;

    public ObservableCollection<TorrentItem> Items { get; } = new();

    public TorrentEngine(Settings settings, HttpClient http)
    {
        _settings = settings;
        _http = http;
        Directory.CreateDirectory(settings.DownloadDir);

        var cacheDir = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            "GameGrab", "cache");
        Directory.CreateDirectory(cacheDir);

        var esBuilder = new EngineSettingsBuilder
        {
            CacheDirectory = cacheDir,
            ListenEndPoints = new Dictionary<string, System.Net.IPEndPoint>
            {
                ["ipv4"] = new(System.Net.IPAddress.Any, settings.ListenPort),
            },
            DhtEndPoint = settings.EnableDht ? new(System.Net.IPAddress.Any, settings.ListenPort) : null,
            MaximumConnections = settings.MaxConnections,
            MaximumUploadRate = settings.MaxUploadKBps * 1024,
            MaximumDownloadRate = settings.MaxDownloadKBps * 1024,
            AllowLocalPeerDiscovery = settings.EnableLsd,
            AllowedEncryption = settings.Encryption switch
            {
                "Forced" => new[] { EncryptionType.RC4Full },
                "Disabled" => new[] { EncryptionType.PlainText },
                _ => new[] { EncryptionType.RC4Full, EncryptionType.RC4Header, EncryptionType.PlainText }
            },
        };

        _engine = new ClientEngine(esBuilder.ToSettings());
        _poll = new Timer(_ => Poll(), null, 500, 500);
    }

    public async Task<TorrentItem> AddMagnetAsync(string magnet, string? displayName = null)
    {
        var link = MagnetLink.Parse(magnet);
        var tm = await _engine.AddAsync(link, _settings.DownloadDir);
        return RegisterAndStart(tm, displayName ?? link.Name ?? "unknown");
    }

    public async Task<TorrentItem> AddTorrentUrlAsync(string url, CancellationToken ct)
    {
        var bytes = await _http.GetByteArrayAsync(url, ct);
        var torrent = await Torrent.LoadAsync(bytes);
        var tm = await _engine.AddAsync(torrent, _settings.DownloadDir);
        return RegisterAndStart(tm, torrent.Name);
    }

    public async Task<TorrentItem> AddTorrentFileAsync(string path)
    {
        var torrent = await Torrent.LoadAsync(path);
        var tm = await _engine.AddAsync(torrent, _settings.DownloadDir);
        return RegisterAndStart(tm, torrent.Name);
    }

    private TorrentItem RegisterAndStart(TorrentManager tm, string name)
    {
        var item = new TorrentItem
        {
            Name = name,
            State = TorrentState.Metadata,
            SavePath = tm.SavePath,
            Hash = tm.InfoHashes?.V1OrV2?.ToHex(),
            Source = "torrent",
        };
        _map[tm] = item;
        Application.Current?.Dispatcher.Invoke(() => Items.Add(item));
        _ = tm.StartAsync();
        return item;
    }

    public async Task PauseAsync(TorrentItem item)
    {
        var tm = Find(item);
        if (tm is not null) { await tm.PauseAsync(); item.State = TorrentState.Paused; }
    }

    public async Task ResumeAsync(TorrentItem item)
    {
        var tm = Find(item);
        if (tm is not null) await tm.StartAsync();
    }

    public async Task RemoveAsync(TorrentItem item, bool deleteFiles)
    {
        var tm = Find(item);
        if (tm is null) return;
        await _engine.RemoveAsync(tm, deleteFiles ? RemoveMode.CacheDataAndDownloadedData : RemoveMode.CacheDataOnly);
        _map.Remove(tm);
        Application.Current?.Dispatcher.Invoke(() => Items.Remove(item));
    }

    private TorrentManager? Find(TorrentItem item) =>
        _map.FirstOrDefault(kv => kv.Value == item).Key;

    private void Poll()
    {
        var app = Application.Current;
        if (app is null) return;
        app.Dispatcher.BeginInvoke(new Action(() =>
        {
            foreach (var (tm, item) in _map)
            {
                try
                {
                    var m = tm.Monitor;
                    item.State = tm.State switch
                    {
                        MonoTorrent.Client.TorrentState.Downloading => TorrentState.Downloading,
                        MonoTorrent.Client.TorrentState.Seeding => TorrentState.Seeding,
                        MonoTorrent.Client.TorrentState.Paused => TorrentState.Paused,
                        MonoTorrent.Client.TorrentState.Metadata => TorrentState.Metadata,
                        MonoTorrent.Client.TorrentState.Hashing => TorrentState.Metadata,
                        MonoTorrent.Client.TorrentState.Error => TorrentState.Error,
                        MonoTorrent.Client.TorrentState.Stopped when tm.Complete => TorrentState.Finished,
                        MonoTorrent.Client.TorrentState.Stopped => TorrentState.Queued,
                        _ => item.State,
                    };
                    item.Progress = tm.Progress;
                    item.Size = tm.Torrent?.Size ?? 0;
                    item.Downloaded = m.DataBytesReceived;
                    item.DownSpeed = m.DownloadRate;
                    item.UpSpeed = m.UploadRate;
                    item.Peers = tm.Peers.Leechs;
                    item.Seeds = tm.Peers.Seeds;
                    item.Eta = ComputeEta(tm);
                }
                catch { }
            }
        }));
    }

    private static string ComputeEta(TorrentManager tm)
    {
        var rate = tm.Monitor.DownloadRate;
        if (rate <= 0 || tm.Torrent is null) return "";
        var remaining = (long)((1 - tm.Progress / 100.0) * tm.Torrent.Size);
        if (remaining <= 0) return "done";
        var seconds = remaining / rate;
        return TimeSpan.FromSeconds(seconds).ToString(@"d\.hh\:mm\:ss");
    }

    public async ValueTask DisposeAsync()
    {
        _poll.Dispose();
        try { await _engine.StopAllAsync(); } catch { }
        _engine.Dispose();
    }
}
