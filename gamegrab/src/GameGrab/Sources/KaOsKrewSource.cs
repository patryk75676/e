using System.Net.Http;
using System.Text.RegularExpressions;
using System.Web;
using HtmlAgilityPack;
using GameGrab.Models;
using GameGrab.Services;

namespace GameGrab.Sources;

public sealed class KaOsKrewSource : ISource
{
    public string Name => "KaOsKrew";
    private const string Base = "https://kaoskrew.org";

    public async Task<IReadOnlyList<SearchResult>> SearchAsync(string query, Settings settings, HttpClient http, CancellationToken ct)
    {
        var url = $"{Base}/?s={HttpUtility.UrlEncode(query)}";
        string html;
        try { html = await http.GetStringAsync(url, ct); }
        catch { return Array.Empty<SearchResult>(); }
        var doc = new HtmlDocument();
        doc.LoadHtml(html);
        var list = new List<SearchResult>();
        var items = doc.DocumentNode.SelectNodes("//article//h2/a | //h1/a");
        if (items is null) return list;
        foreach (var a in items.Take(40))
        {
            list.Add(new SearchResult
            {
                Source = Name,
                Title = HtmlEntity.DeEntitize(a.InnerText).Trim(),
                DetailUrl = a.GetAttributeValue("href", ""),
                Kind = ReleaseKind.Magnet,
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
        foreach (var a in doc.DocumentNode.SelectNodes("//a[@href]") ?? Enumerable.Empty<HtmlNode>())
        {
            var href = a.GetAttributeValue("href", "");
            if (Regex.IsMatch(href, @"(pixeldrain|gofile|1fichier|datanodes|buzzheavier|mega\.nz|fuckingfast|filecrypt)", RegexOptions.IgnoreCase))
                result.DirectUrls.Add(href);
        }
        return result;
    }
}
