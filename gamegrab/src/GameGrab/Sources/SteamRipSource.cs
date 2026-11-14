using System.Net.Http;
using System.Text.RegularExpressions;
using System.Web;
using HtmlAgilityPack;
using GameGrab.Models;
using GameGrab.Services;

namespace GameGrab.Sources;

public sealed class SteamRipSource : ISource
{
    public string Name => "SteamRIP";
    private const string Base = "https://steamrip.com";

    public async Task<IReadOnlyList<SearchResult>> SearchAsync(string query, Settings settings, HttpClient http, CancellationToken ct)
    {
        var url = $"{Base}/?s={HttpUtility.UrlEncode(query)}";
        var html = await http.GetStringAsync(url, ct);
        var doc = new HtmlDocument();
        doc.LoadHtml(html);
        var list = new List<SearchResult>();
        var items = doc.DocumentNode.SelectNodes("//article//h2/a | //h2[contains(@class,'entry-title')]/a");
        if (items is null) return list;
        foreach (var a in items)
        {
            list.Add(new SearchResult
            {
                Source = Name,
                Title = HtmlEntity.DeEntitize(a.InnerText).Trim(),
                DetailUrl = a.GetAttributeValue("href", ""),
                Kind = ReleaseKind.DirectHttp,
            });
        }
        return list;
    }

    public async Task<SearchResult> ResolveAsync(SearchResult result, Settings settings, HttpClient http, CancellationToken ct)
    {
        var html = await http.GetStringAsync(result.DetailUrl, ct);
        var doc = new HtmlDocument();
        doc.LoadHtml(html);
        var links = doc.DocumentNode.SelectNodes("//a[@href]");
        if (links is null) return result;
        foreach (var a in links)
        {
            var href = a.GetAttributeValue("href", "");
            if (Regex.IsMatch(href, @"(megadb|megaup|1fichier|pixeldrain|gofile|buzzheavier|filecrypt)", RegexOptions.IgnoreCase))
                result.DirectUrls.Add(href);
        }
        return result;
    }
}
