using System.Net.Http;
using System.Text.RegularExpressions;
using System.Web;
using HtmlAgilityPack;
using GameGrab.Models;
using GameGrab.Services;

namespace GameGrab.Sources;

public sealed class DodiSource : ISource
{
    public string Name => "DODI";
    private const string Base = "https://dodi-repacks.site";

    public async Task<IReadOnlyList<SearchResult>> SearchAsync(string query, Settings settings, HttpClient http, CancellationToken ct)
    {
        var url = $"{Base}/?s={HttpUtility.UrlEncode(query)}";
        var html = await http.GetStringAsync(url, ct);
        var doc = new HtmlDocument();
        doc.LoadHtml(html);
        var list = new List<SearchResult>();
        var articles = doc.DocumentNode.SelectNodes("//article");
        if (articles is null) return list;
        foreach (var a in articles)
        {
            var link = a.SelectSingleNode(".//h2/a") ?? a.SelectSingleNode(".//h1/a");
            if (link is null) continue;
            var sizeM = Regex.Match(a.InnerText, @"Repack Size\s*[:\-]\s*([\d.,]+\s*[KMGT]B)", RegexOptions.IgnoreCase);
            list.Add(new SearchResult
            {
                Source = Name,
                Title = HtmlEntity.DeEntitize(link.InnerText).Trim(),
                DetailUrl = link.GetAttributeValue("href", ""),
                Size = sizeM.Success ? sizeM.Groups[1].Value : "",
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
        var links = doc.DocumentNode.SelectNodes("//a[@href]");
        if (links is not null)
        {
            foreach (var a in links)
            {
                var href = a.GetAttributeValue("href", "");
                if (Regex.IsMatch(href, @"(1fichier|datanodes|fuckingfast|pixeldrain|gofile|filecrypt|rapidgator)", RegexOptions.IgnoreCase))
                    result.DirectUrls.Add(href);
            }
        }
        return result;
    }
}
