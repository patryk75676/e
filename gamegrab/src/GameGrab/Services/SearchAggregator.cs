using System.Collections.Concurrent;
using System.Net.Http;
using GameGrab.Models;
using GameGrab.Sources;

namespace GameGrab.Services;

public sealed class SearchAggregator
{
    private readonly Dictionary<string, ISource> _sources;
    private readonly HttpClient _http;

    public SearchAggregator(IEnumerable<ISource> sources, HttpClient http)
    {
        _sources = sources.ToDictionary(s => s.Name, StringComparer.OrdinalIgnoreCase);
        _http = http;
    }

    public IReadOnlyCollection<string> Available => _sources.Keys;

    public async IAsyncEnumerable<SearchResult> SearchAsync(
        string query,
        Settings settings,
        [System.Runtime.CompilerServices.EnumeratorCancellation] CancellationToken ct = default)
    {
        var enabled = settings.Sources
            .Where(_sources.ContainsKey)
            .Select(name => _sources[name])
            .ToArray();

        var channel = System.Threading.Channels.Channel.CreateUnbounded<SearchResult>();
        var tasks = enabled.Select(src => Task.Run(async () =>
        {
            try
            {
                var found = await src.SearchAsync(query, settings, _http, ct);
                foreach (var item in found)
                    await channel.Writer.WriteAsync(item, ct);
            }
            catch { /* source unreachable or blocked, skip */ }
        }, ct)).ToArray();

        _ = Task.Run(async () => { try { await Task.WhenAll(tasks); } finally { channel.Writer.TryComplete(); } });

        await foreach (var r in channel.Reader.ReadAllAsync(ct))
            yield return r;
    }

    public Task<SearchResult> ResolveAsync(SearchResult result, Settings settings, CancellationToken ct)
    {
        if (!_sources.TryGetValue(result.Source, out var src))
            return Task.FromResult(result);
        return src.ResolveAsync(result, settings, _http, ct);
    }
}
