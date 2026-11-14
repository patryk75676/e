using System.Net.Http;
using System.Text.Json;
using System.Web;
using GameGrab.Models;
using GameGrab.Services;

namespace GameGrab.Sources;

public sealed class TpbSource : ISource
{
    public string Name => "TPB";
    private const string Api = "https://apibay.org";

    public async Task<IReadOnlyList<SearchResult>> SearchAsync(string query, Settings settings, HttpClient http, CancellationToken ct)
    {
        var url = $"{Api}/q.php?q={HttpUtility.UrlEncode(query)}&cat=400";
        var json = await http.GetStringAsync(url, ct);
        using var doc = JsonDocument.Parse(json);
        var list = new List<SearchResult>();
        foreach (var it in doc.RootElement.EnumerateArray())
        {
            var id = it.GetProperty("id").GetString();
            if (id == "0") continue;
            var name = it.GetProperty("name").GetString() ?? "";
            var hash = it.GetProperty("info_hash").GetString() ?? "";
            long.TryParse(it.GetProperty("size").GetString(), out var size);
            int.TryParse(it.GetProperty("seeders").GetString(), out var seeds);
            int.TryParse(it.GetProperty("leechers").GetString(), out var leech);

            list.Add(new SearchResult
            {
                Source = Name,
                Title = name,
                DetailUrl = $"https://thepiratebay.org/description.php?id={id}",
                Size = HumanSize(size),
                SizeBytes = size,
                Seeders = seeds,
                Leechers = leech,
                Magnet = string.IsNullOrEmpty(hash) ? null : Trackers.BuildMagnet(hash, name),
                Kind = ReleaseKind.Magnet,
            });
        }
        return list;
    }

    public Task<SearchResult> ResolveAsync(SearchResult result, Settings settings, HttpClient http, CancellationToken ct)
        => Task.FromResult(result);

    private static string HumanSize(long b)
    {
        string[] u = { "B", "KB", "MB", "GB", "TB" };
        double f = b;
        int i = 0;
        while (f >= 1024 && i < u.Length - 1) { f /= 1024; i++; }
        return $"{f:0.##} {u[i]}";
    }
}
