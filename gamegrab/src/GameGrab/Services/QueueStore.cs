using System.IO;
using System.Text.Json;

namespace GameGrab.Services;

public sealed class QueueEntry
{
    public string? Magnet { get; set; }
    public string? TorrentFile { get; set; }
    public string Name { get; set; } = "";
    public string SavePath { get; set; } = "";
    public string Source { get; set; } = "torrent";
    public bool Paused { get; set; }
}

public sealed class QueueStore
{
    public static string Path => System.IO.Path.Combine(Settings.Root, "queue.json");

    public List<QueueEntry> Entries { get; set; } = new();

    public static QueueStore Load()
    {
        try
        {
            if (File.Exists(Path))
            {
                var q = JsonSerializer.Deserialize<QueueStore>(File.ReadAllText(Path));
                if (q is not null) return q;
            }
        }
        catch { }
        return new QueueStore();
    }

    public void Save()
    {
        File.WriteAllText(Path, JsonSerializer.Serialize(this,
            new JsonSerializerOptions { WriteIndented = true }));
    }
}
