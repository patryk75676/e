using System.Net.Http;
using System.Text.Json;
using System.Web;
using GameGrab.Models;

namespace GameGrab.Services;

public sealed class SteamStoreApi
{
    private readonly HttpClient _http;
    public SteamStoreApi(HttpClient http) => _http = http;

    public async Task<IReadOnlyList<SteamApp>> SearchAsync(string term, CancellationToken ct = default)
    {
        if (string.IsNullOrWhiteSpace(term)) return Array.Empty<SteamApp>();
        var url = $"https://store.steampowered.com/api/storesearch/?term={HttpUtility.UrlEncode(term)}&l=en&cc=us";
        var json = await _http.GetStringAsync(url, ct);
        using var doc = JsonDocument.Parse(json);
        var list = new List<SteamApp>();
        if (!doc.RootElement.TryGetProperty("items", out var items)) return list;
        foreach (var it in items.EnumerateArray())
        {
            if (!it.TryGetProperty("id", out var idEl) || !it.TryGetProperty("name", out var nameEl)) continue;
            var appId = idEl.GetInt32();
            var header = it.TryGetProperty("tiny_image", out var ti) ? ti.GetString() ?? "" : "";
            list.Add(new SteamApp
            {
                AppId = appId,
                Name = nameEl.GetString() ?? "",
                HeaderUrl = header.Length > 0 ? header : $"https://cdn.cloudflare.steamstatic.com/steam/apps/{appId}/header.jpg",
                CapsuleUrl = $"https://cdn.cloudflare.steamstatic.com/steam/apps/{appId}/capsule_231x87.jpg",
                IconUrl    = $"https://cdn.cloudflare.steamstatic.com/steam/apps/{appId}/capsule_sm_120.jpg",
                Type = it.TryGetProperty("type", out var t) ? t.GetString() ?? "game" : "game",
            });
        }
        return list;
    }

    public async Task<SteamApp?> DetailsAsync(int appId, CancellationToken ct = default)
    {
        var url = $"https://store.steampowered.com/api/appdetails?appids={appId}&l=en&cc=us";
        var json = await _http.GetStringAsync(url, ct);
        using var doc = JsonDocument.Parse(json);
        var key = appId.ToString();
        if (!doc.RootElement.TryGetProperty(key, out var entry)) return null;
        if (!entry.TryGetProperty("data", out var data)) return null;
        var name = data.TryGetProperty("name", out var n) ? n.GetString() ?? "" : "";
        var developer = "";
        if (data.TryGetProperty("developers", out var devs) && devs.ValueKind == JsonValueKind.Array && devs.GetArrayLength() > 0)
            developer = devs[0].GetString() ?? "";
        var releaseDate = "";
        if (data.TryGetProperty("release_date", out var rd) && rd.TryGetProperty("date", out var rds))
            releaseDate = rds.GetString() ?? "";
        return new SteamApp
        {
            AppId = appId,
            Name = name,
            Developer = developer,
            ReleaseDate = releaseDate,
            HeaderUrl  = $"https://cdn.cloudflare.steamstatic.com/steam/apps/{appId}/header.jpg",
            CapsuleUrl = $"https://cdn.cloudflare.steamstatic.com/steam/apps/{appId}/capsule_616x353.jpg",
            IconUrl    = $"https://cdn.cloudflare.steamstatic.com/steam/apps/{appId}/capsule_sm_120.jpg",
        };
    }
}
