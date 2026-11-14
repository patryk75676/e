using System.Web;

namespace GameGrab.Sources;

internal static class Trackers
{
    public static readonly string[] Udp =
    {
        "udp://tracker.opentrackr.org:1337/announce",
        "udp://open.stealth.si:80/announce",
        "udp://tracker.torrent.eu.org:451/announce",
        "udp://exodus.desync.com:6969/announce",
        "udp://opentracker.i2p.rocks:6969/announce",
        "udp://tracker.openbittorrent.com:6969/announce",
        "udp://tracker.dler.org:6969/announce",
        "udp://explodie.org:6969/announce",
        "udp://tracker.moeking.me:6969/announce",
    };

    public static string BuildMagnet(string infoHash, string name)
    {
        var sb = new System.Text.StringBuilder();
        sb.Append("magnet:?xt=urn:btih:").Append(infoHash);
        sb.Append("&dn=").Append(HttpUtility.UrlEncode(name));
        foreach (var t in Udp)
            sb.Append("&tr=").Append(HttpUtility.UrlEncode(t));
        return sb.ToString();
    }
}
