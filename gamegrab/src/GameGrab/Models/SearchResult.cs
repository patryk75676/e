namespace GameGrab.Models;

public enum ReleaseKind
{
    Torrent,
    Magnet,
    DirectHttp
}

public sealed class SearchResult
{
    public required string Source { get; init; }
    public required string Title { get; init; }
    public required string DetailUrl { get; init; }
    public string Size { get; init; } = "";
    public long SizeBytes { get; init; }
    public int Seeders { get; init; }
    public int Leechers { get; init; }
    public string Uploaded { get; init; } = "";
    public ReleaseKind Kind { get; init; } = ReleaseKind.Torrent;
    public string? Magnet { get; set; }
    public string? TorrentUrl { get; set; }
    public List<string> DirectUrls { get; init; } = new();
    public Dictionary<string, string> Extra { get; init; } = new();

    public int Score =>
        Seeders * 3
        + (!string.IsNullOrEmpty(Magnet) ? 2 : 0)
        + (!string.IsNullOrEmpty(TorrentUrl) ? 1 : 0)
        + (DirectUrls.Count > 0 ? 1 : 0);
}
