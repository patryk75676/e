using System.Collections.ObjectModel;
using System.IO;
using System.Net.Http;
using System.Windows;
using MonoTorrent;
using MonoTorrent.Client;
using GameGrab.Models;
using GgState = GameGrab.Models.TorrentState;
using MtState = MonoTorrent.Client.TorrentState;

namespace GameGrab.Services;

public sealed class TorrentEngine : IAsyncDisposable
{
    private readonly ClientEngine _engine;
    private readonly HttpClient _http;
    private readonly Settings _settings;
    private readonly QueueStore _queue;
    private readonly Dictionary<TorrentManager, TorrentItem> _map = new();
    private readonly Timer _poll;

    public ObservableCollection<TorrentItem> Items { get; } = new();
    public event Action<TorrentItem>? TorrentCompleted;
    public event Action<long, long>? RatesUpdated; // totalDown, totalUp (bytes/s)

    public TorrentEngine(Settings settings, HttpClient http, QueueStore queue)
    {
        _settings = settings;
        _http = http;
        _queue = queue;
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
        };

        _engine = new ClientEngine(esBuilder.ToSettings());
        _poll = new Timer(_ => Poll(), null, 500, 500);
    }

    public async Task RestoreAsync()
    {
        if (!_settings.AutoResumeOnStart) return;
        foreach (var e in _queue.Entries.ToList())
        {
            try
            {
                if (!string.IsNullOrEmpty(e.Magnet))
                    await AddMagnetAsync(e.Magnet!, e.Name, persist: false, paused: e.Paused);
                else if (!string.IsNullOrEmpty(e.TorrentFile) && File.Exists(e.TorrentFile))
                    await AddTorrentFileAsync(e.TorrentFile!, persist: false, paused: e.Paused);
            }
            catch { }
        }
    }

    public async Task<TorrentItem> AddMagnetAsync(string magnet, string? displayName = null, bool persist = true, bool paused = false)
    {
        var link = MagnetLink.Parse(magnet);
        var tm = await _engine.AddAsync(link, _settings.DownloadDir);
        var item = Register(tm, displayName ?? link.Name ?? "unknown", "magnet");
        if (!paused) await tm.StartAsync();
        if (persist)
        {
            _queue.Entries.Add(new QueueEntry { Magnet = magnet, Name = item.Name, SavePath = tm.SavePath, Paused = paused });
            _queue.Save();
        }
        return item;
    }

    public async Task<TorrentItem> AddTorrentUrlAsync(string url, CancellationToken ct)
    {
        var bytes = await _http.GetByteArrayAsync(url, ct);
        var tmpDir = Path.Combine(Settings.Root, "torrents");
        Directory.CreateDirectory(tmpDir);
        var file = Path.Combine(tmpDir, $"{Guid.NewGuid():N}.torrent");
        await File.WriteAllBytesAsync(file, bytes, ct);
        return await AddTorrentFileAsync(file);
    }

    public async Task<TorrentItem> AddTorrentFileAsync(string path, bool persist = true, bool paused = false)
    {
        var torrent = await Torrent.LoadAsync(path);
        var tm = await _engine.AddAsync(torrent, _settings.DownloadDir);
        var item = Register(tm, torrent.Name, "file");
        if (!paused) await tm.StartAsync();
        if (persist)
        {
            _queue.Entries.Add(new QueueEntry { TorrentFile = path, Name = torrent.Name, SavePath = tm.SavePath, Paused = paused });
            _queue.Save();
        }
        return item;
    }

    private TorrentItem Register(TorrentManager tm, string name, string source)
    {
        var item = new TorrentItem
        {
            Name = name,
            State = GgState.Metadata,
            SavePath = tm.SavePath,
            Hash = tm.InfoHashes?.V1OrV2?.ToHex(),
            Source = source,
        };
        _map[tm] = item;
        Application.Current?.Dispatcher.Invoke(() => Items.Add(item));
        return item;
    }

    public async Task PauseAsync(TorrentItem item)
    {
        var tm = Find(item);
        if (tm is not null) { await tm.PauseAsync(); item.State = GgState.Paused; }
    }

    public async Task ResumeAsync(TorrentItem item)
    {
        var tm = Find(item);
        if (tm is not null) await tm.StartAsync();
    }

    public async Task ForceRecheckAsync(TorrentItem item)
    {
        var tm = Find(item);
        if (tm is null) return;
        await tm.StopAsync();
        await tm.HashCheckAsync(true);
    }

    public async Task RemoveAsync(TorrentItem item, bool deleteFiles)
    {
        var tm = Find(item);
        if (tm is null) return;
        await _engine.RemoveAsync(tm, deleteFiles ? RemoveMode.CacheDataAndDownloadedData : RemoveMode.CacheDataOnly);
        _map.Remove(tm);
        Application.Current?.Dispatcher.Invoke(() => Items.Remove(item));
        _queue.Entries.RemoveAll(q => q.Name == item.Name);
        _queue.Save();
    }

    public string? GetMagnet(TorrentItem item)
    {
        var tm = Find(item);
        var ih = tm?.InfoHashes?.V1OrV2?.ToHex();
        return ih is null ? null : Sources.Trackers.BuildMagnet(ih, item.Name);
    }

    private TorrentManager? Find(TorrentItem item) =>
        _map.FirstOrDefault(kv => kv.Value == item).Key;

    private void Poll()
    {
        var app = Application.Current;
        if (app is null) return;
        app.Dispatcher.BeginInvoke(new Action(() =>
        {
            long totalDown = 0, totalUp = 0;
            foreach (var (tm, item) in _map)
            {
                try
                {
                    var m = tm.Monitor;
                    var prevState = item.State;
                    item.State = tm.State switch
                    {
                        MtState.Downloading => GgState.Downloading,
                        MtState.Seeding => GgState.Seeding,
                        MtState.Paused => GgState.Paused,
                        MtState.Metadata => GgState.Metadata,
                        MtState.Hashing => GgState.Metadata,
                        MtState.Error => GgState.Error,
                        MtState.Stopped when tm.Complete => GgState.Finished,
                        MtState.Stopped => GgState.Queued,
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
                    totalDown += m.DownloadRate;
                    totalUp += m.UploadRate;

                    if (prevState != GgState.Finished &&
                        item.State == GgState.Finished)
                        TorrentCompleted?.Invoke(item);
                }
                catch { }
            }
            RatesUpdated?.Invoke(totalDown, totalUp);
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
