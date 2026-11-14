using System.Net.Http;
using System.Text.RegularExpressions;
using System.Web;
using HtmlAgilityPack;
using GameGrab.Models;
using GameGrab.Services;

namespace GameGrab.Sources;

public sealed class FitGirlSource : ISource
{
    public string Name => "FitGirl";
    private const string Base = "https://fitgirl-repacks.site";

    public async Task<IReadOnlyList<SearchResult>> SearchAsync(string query, Settings settings, HttpClient http, CancellationToken ct)
    {
        var url = $"{Base}/?s={HttpUtility.UrlEncode(query)}";
        var html = await http.GetStringAsync(url, ct);
        var doc = new HtmlDocument();
        doc.LoadHtml(html);
        var articles = doc.DocumentNode.SelectNodes("//article");
        var list = new List<SearchResult>();
        if (articles is null) return list;
        foreach (var a in articles)
        {
            var titleA = a.SelectSingleNode(".//h1[contains(@class,'entry-title')]/a") ??
                         a.SelectSingleNode(".//h2/a");
            if (titleA is null) continue;
            var title = HtmlEntity.DeEntitize(titleA.InnerText).Trim();
            var href = titleA.GetAttributeValue("href", "");
            var sizeM = Regex.Match(a.InnerText, @"Repack Size:\s*([\d.,]+\s*[KMGT]B)", RegexOptions.IgnoreCase);
            list.Add(new SearchResult
            {
                Source = Name,
                Title = title,
                DetailUrl = href,
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

        var magnet = doc.DocumentNode.SelectSingleNode("//a[starts-with(@href,'magnet:')]");
        if (magnet is not null) result.Magnet = magnet.GetAttributeValue("href", "");

        // collect direct mirrors (DataNodes, 1fichier, FuckingFast etc.)
        var links = doc.DocumentNode.SelectNodes("//a[@href]");
        if (links is not null)
        {
            foreach (var a in links)
            {
                var href = a.GetAttributeValue("href", "");
                if (href.StartsWith("magnet:")) continue;
                if (Regex.IsMatch(href, @"(1fichier|datanodes|fuckingfast|pixeldrain|gofile|filecrypt|rapidgator)", RegexOptions.IgnoreCase))
                    result.DirectUrls.Add(href);
            }
        }
        return result;
    }
}
