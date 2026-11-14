using System.Net;
using System.Net.Http;
using System.Net.Http.Headers;

namespace GameGrab.Services;

public static class HttpFactory
{
    public static HttpClient Create(Settings settings)
    {
        var handler = new HttpClientHandler
        {
            AutomaticDecompression = DecompressionMethods.All,
            CookieContainer = new CookieContainer(),
            UseCookies = true,
            AllowAutoRedirect = true,
            MaxAutomaticRedirections = 10,
        };
        var http = new HttpClient(handler)
        {
            Timeout = TimeSpan.FromSeconds(25),
        };
        http.DefaultRequestHeaders.UserAgent.ParseAdd(settings.UserAgent);
        http.DefaultRequestHeaders.Accept.ParseAdd("text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
        http.DefaultRequestHeaders.AcceptLanguage.ParseAdd("en-US,en;q=0.9");
        http.DefaultRequestHeaders.AcceptEncoding.Add(new StringWithQualityHeaderValue("gzip"));
        http.DefaultRequestHeaders.AcceptEncoding.Add(new StringWithQualityHeaderValue("deflate"));
        http.DefaultRequestHeaders.AcceptEncoding.Add(new StringWithQualityHeaderValue("br"));
        return http;
    }
}
