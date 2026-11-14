using System.Net.Http;
using System.Text;
using System.Text.RegularExpressions;
using System.Web;
using HtmlAgilityPack;
using GameGrab.Models;
using GameGrab.Services;

namespace GameGrab.Sources;

public sealed class RuTrackerSource : ISource
{
    public string Name => "RuTracker";
    private const string Base = "https://rutracker.org";
    private bool _loggedIn;

    public async Task<IReadOnlyList<SearchResult>> SearchAsync(string query, Settings settings, HttpClient http, CancellationToken ct)
    {
        if (!_loggedIn && !string.IsNullOrEmpty(settings.RuTrackerUser))
            await LoginAsync(http, settings, ct);

        var url = $"{Base}/forum/tracker.php";
        var form = new FormUrlEncodedContent(new[]
        {
            new KeyValuePair<string,string>("nm", query),
            new KeyValuePair<string,string>("f", "127,635,1992,884,2203,2172,655,660,2366,1960,139,920,2514,945,2242"),
        });
        using var r = await http.PostAsync(url, form, ct);
        var bytes = await r.Content.ReadAsByteArrayAsync(ct);
        var html = Encoding.GetEncoding(1251).GetString(bytes);
        var doc = new HtmlDocument();
        doc.LoadHtml(html);
        var rows = doc.DocumentNode.SelectNodes("//tr[contains(@class,'hl-tr')]");
        var list = new List<SearchResult>();
        if (rows is null) return list;
        foreach (var row in rows)
        {
            var a = row.SelectSingleNode(".//a[contains(@class,'tLink')]");
            if (a is null) continue;
            var title = HtmlEntity.DeEntitize(a.InnerText).Trim();
            var href = Base + "/forum/" + a.GetAttributeValue("href", "").TrimStart('/');
            var sizeNode = row.SelectSingleNode(".//td[contains(@class,'tor-size')]//a");
            var seedsNode = row.SelectSingleNode(".//b[contains(@class,'seedmed')]");
            int.TryParse(seedsNode?.InnerText?.Trim(), out var seeds);
            list.Add(new SearchResult
            {
                Source = Name,
                Title = title,
                DetailUrl = href,
                Size = sizeNode?.InnerText?.Trim() ?? "",
                Seeders = seeds,
                Kind = ReleaseKind.Torrent,
            });
        }
        return list;
    }

    private async Task LoginAsync(HttpClient http, Settings s, CancellationToken ct)
    {
        var form = new FormUrlEncodedContent(new[]
        {
            new KeyValuePair<string,string>("login_username", s.RuTrackerUser),
            new KeyValuePair<string,string>("login_password", s.RuTrackerPass),
            new KeyValuePair<string,string>("login", "Вход"),
        });
        using var r = await http.PostAsync($"{Base}/forum/login.php", form, ct);
        _loggedIn = r.IsSuccessStatusCode;
    }

    public async Task<SearchResult> ResolveAsync(SearchResult result, Settings settings, HttpClient http, CancellationToken ct)
    {
        var html = await http.GetStringAsync(result.DetailUrl, ct);
        var m = Regex.Match(html, @"magnet:\?[^""'<\s]+");
        if (m.Success) result.Magnet = HttpUtility.HtmlDecode(m.Value);
        var dl = Regex.Match(html, @"/forum/dl\.php\?t=(\d+)");
        if (dl.Success) result.TorrentUrl = $"{Base}/forum/dl.php?t={dl.Groups[1].Value}";
        return result;
    }
}
