using System.Net.Http;
using System.Text.RegularExpressions;
using System.Web;
using HtmlAgilityPack;
using GameGrab.Models;
using GameGrab.Services;

namespace GameGrab.Sources;

public sealed class GogGamesSource : ISource
{
    public string Name => "GOGGames";
    private const string Base = "https://gog-games.to";

    public async Task<IReadOnlyList<SearchResult>> SearchAsync(string query, Settings settings, HttpClient http, CancellationToken ct)
    {
        var url = $"{Base}/search/{HttpUtility.UrlEncode(query)}";
        var html = await http.GetStringAsync(url, ct);
        var doc = new HtmlDocument();
        doc.LoadHtml(html);
        var list = new List<SearchResult>();
        var cards = doc.DocumentNode.SelectNodes("//a[contains(@class,'game-card')] | //div[contains(@class,'games')]//a[contains(@href,'/game/')]");
        if (cards is null) return list;
        foreach (var a in cards)
        {
            var href = a.GetAttributeValue("href", "");
            if (!href.Contains("/game/")) continue;
            if (!href.StartsWith("http")) href = Base + href;
            var title = HtmlEntity.DeEntitize(a.InnerText).Trim();
            if (string.IsNullOrEmpty(title)) title = href.Split('/')[^1];
            list.Add(new SearchResult
            {
                Source = Name,
                Title = title,
                DetailUrl = href,
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
        var m = doc.DocumentNode.SelectSingleNode("//a[starts-with(@href,'magnet:')]");
        if (m is not null) result.Magnet = m.GetAttributeValue("href", "");
        var links = doc.DocumentNode.SelectNodes("//a[@href]");
        if (links is null) return result;
        foreach (var a in links)
        {
            var href = a.GetAttributeValue("href", "");
            if (Regex.IsMatch(href, @"(pixeldrain|gofile|1fichier|datanodes|mega\.nz|buzzheavier)", RegexOptions.IgnoreCase))
                result.DirectUrls.Add(href);
        }
        return result;
    }
}
