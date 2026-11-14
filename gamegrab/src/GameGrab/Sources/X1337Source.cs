using System.Net.Http;
using System.Web;
using HtmlAgilityPack;
using GameGrab.Models;
using GameGrab.Services;

namespace GameGrab.Sources;

public sealed class X1337Source : ISource
{
    public string Name => "1337x";

    private static readonly string[] Mirrors =
    {
        "https://1337x.to",
        "https://1337x.st",
        "https://x1337x.ws",
        "https://1337x.unblockit.bet",
    };

    private static string? _activeMirror;

    private async Task<string> PickMirrorAsync(HttpClient http, CancellationToken ct)
    {
        if (_activeMirror is not null) return _activeMirror;
        foreach (var m in Mirrors)
        {
            try
            {
                using var r = await http.GetAsync(m, HttpCompletionOption.ResponseHeadersRead, ct);
                if (r.IsSuccessStatusCode) { _activeMirror = m; return m; }
            }
            catch { }
        }
        _activeMirror = Mirrors[0];
        return _activeMirror;
    }

    public async Task<IReadOnlyList<SearchResult>> SearchAsync(string query, Settings settings, HttpClient http, CancellationToken ct)
    {
        var baseUrl = await PickMirrorAsync(http, ct);
        var url = $"{baseUrl}/category-search/{HttpUtility.UrlEncode(query)}/Games/1/";
        var html = await http.GetStringAsync(url, ct);
        var doc = new HtmlDocument();
        doc.LoadHtml(html);

        var rows = doc.DocumentNode.SelectNodes("//table[contains(@class,'table-list')]//tbody/tr");
        var list = new List<SearchResult>();
        if (rows is null) return list;

        foreach (var row in rows)
        {
            var links = row.SelectNodes(".//td[contains(@class,'name')]/a");
            if (links is null || links.Count < 2) continue;
            var href = links[1].GetAttributeValue("href", "");
            var title = HtmlEntity.DeEntitize(links[1].InnerText).Trim();

            int.TryParse(row.SelectSingleNode(".//td[contains(@class,'seeds')]")?.InnerText?.Trim(), out var seeds);
            int.TryParse(row.SelectSingleNode(".//td[contains(@class,'leeches')]")?.InnerText?.Trim(), out var leech);
            var sizeNode = row.SelectSingleNode(".//td[contains(@class,'size')]");
            var size = sizeNode?.FirstChild?.InnerText?.Trim() ?? "";
            var uploaded = row.SelectSingleNode(".//td[contains(@class,'coll-date')]")?.InnerText?.Trim() ?? "";

            list.Add(new SearchResult
            {
                Source = Name,
                Title = title,
                DetailUrl = baseUrl + href,
                Size = size,
                Seeders = seeds,
                Leechers = leech,
                Uploaded = uploaded,
                Kind = ReleaseKind.Magnet,
            });
        }

        return list;
    }

    public async Task<SearchResult> ResolveAsync(SearchResult result, Settings settings, HttpClient http, CancellationToken ct)
    {
        if (!string.IsNullOrEmpty(result.Magnet)) return result;
        var html = await http.GetStringAsync(result.DetailUrl, ct);
        var doc = new HtmlDocument();
        doc.LoadHtml(html);
        var a = doc.DocumentNode.SelectSingleNode("//a[starts-with(@href,'magnet:')]");
        if (a is not null) result.Magnet = a.GetAttributeValue("href", "");
        return result;
    }
}
