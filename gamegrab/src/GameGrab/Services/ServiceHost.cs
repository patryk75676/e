using System.Net.Http;
using GameGrab.Sources;

namespace GameGrab.Services;

public interface IServiceHost
{
    Settings Settings { get; }
    SearchAggregator Search { get; }
    TorrentEngine Torrents { get; }
    HttpDownloader Downloader { get; }
    HttpClient Http { get; }
    void Start();
    Task StopAsync();
}

public sealed class ServiceHost : IServiceHost
{
    public Settings Settings { get; } = Settings.Load();
    public HttpClient Http { get; }
    public SearchAggregator Search { get; }
    public TorrentEngine Torrents { get; }
    public HttpDownloader Downloader { get; }

    public ServiceHost()
    {
        Http = HttpFactory.Create(Settings);
        var sources = new ISource[]
        {
            new X1337Source(),
            new TpbSource(),
            new FitGirlSource(),
            new DodiSource(),
            new SteamRipSource(),
            new OnlineFixSource(),
            new GogGamesSource(),
            new RuTrackerSource(),
        };
        Search = new SearchAggregator(sources, Http);
        Torrents = new TorrentEngine(Settings, Http);
        Downloader = new HttpDownloader(Http);
    }

    public void Start() { }
    public async Task StopAsync() => await Torrents.DisposeAsync();
}
