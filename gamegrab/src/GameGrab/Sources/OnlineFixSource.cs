using System.Net.Http;
using System.Text.RegularExpressions;
using System.Web;
using HtmlAgilityPack;
using GameGrab.Models;
using GameGrab.Services;

namespace GameGrab.Sources;

public sealed class OnlineFixSource : ISource
{
    public string Name => "OnlineFix";
    private const string Base = "https://online-fix.me";

    public async Task<IReadOnlyList<SearchResult>> SearchAsync(string query, Settings settings, HttpClient http, CancellationToken ct)
    {
        var url = $"{Base}/index.php?do=search";
        var form = new FormUrlEncodedContent(new[]
        {
            new KeyValuePair<string,string>("do","search"),
            new KeyValuePair<string,string>("subaction","search"),
            new KeyValuePair<string,string>("story", query),
        });
        using var r = await http.PostAsync(url, form, ct);
        var html = await r.Content.ReadAsStringAsync(ct);
        var doc = new HtmlDocument();
        doc.LoadHtml(html);
        var list = new List<SearchResult>();
        var items = doc.DocumentNode.SelectNodes("//article//h2/a | //div[contains(@class,'news')]//a[contains(@class,'title')]");
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
            if (href.StartsWith("magnet:")) { result.Magnet = href; continue; }
            if (Regex.IsMatch(href, @"(online-fix|pixeldrain|gofile|1fichier|mega\.nz|torrent)", RegexOptions.IgnoreCase))
                result.DirectUrls.Add(href);
        }
        return result;
    }
}
