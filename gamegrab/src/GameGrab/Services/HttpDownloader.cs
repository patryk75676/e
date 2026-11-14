using System.IO;
using System.Net.Http;
using GameGrab.Models;

namespace GameGrab.Services;

public sealed class HttpDownloader
{
    private readonly HttpClient _http;
    public HttpDownloader(HttpClient http) => _http = http;

    public async Task<string> DownloadAsync(string url, string destDir, IProgress<(long done, long total, long rate)>? progress, CancellationToken ct)
    {
        Directory.CreateDirectory(destDir);
        using var req = new HttpRequestMessage(HttpMethod.Get, url);
        using var resp = await _http.SendAsync(req, HttpCompletionOption.ResponseHeadersRead, ct);
        resp.EnsureSuccessStatusCode();

        var fileName = resp.Content.Headers.ContentDisposition?.FileNameStar ??
                       resp.Content.Headers.ContentDisposition?.FileName?.Trim('"') ??
                       Path.GetFileName(new Uri(url).LocalPath);
        if (string.IsNullOrWhiteSpace(fileName)) fileName = $"download-{DateTime.UtcNow.Ticks}.bin";
        var dest = Path.Combine(destDir, fileName);

        var total = resp.Content.Headers.ContentLength ?? -1L;
        await using var src = await resp.Content.ReadAsStreamAsync(ct);
        await using var dst = new FileStream(dest, FileMode.Create, FileAccess.Write, FileShare.None, 1 << 16, useAsync: true);

        var buffer = new byte[1 << 16];
        long done = 0;
        var start = Environment.TickCount64;
        int read;
        while ((read = await src.ReadAsync(buffer.AsMemory(), ct)) > 0)
        {
            await dst.WriteAsync(buffer.AsMemory(0, read), ct);
            done += read;
            var elapsed = Math.Max(1, Environment.TickCount64 - start);
            var rate = done * 1000 / elapsed;
            progress?.Report((done, total, rate));
        }
        return dest;
    }
}
