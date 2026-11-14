using System.IO;
using System.Text.Json;

namespace GameGrab.Services;

public sealed class Settings
{
    public string DownloadDir { get; set; } =
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile),
                     "Downloads", "GameGrab");

    public int ListenPort { get; set; } = 6881;
    public int MaxConnections { get; set; } = 400;
    public int MaxUploadKBps { get; set; } = 0;
    public int MaxDownloadKBps { get; set; } = 0;
    public bool EnableDht { get; set; } = true;
    public bool EnableLsd { get; set; } = true;
    public bool EnableUtp { get; set; } = true;
    public string Encryption { get; set; } = "Enabled";
    public List<string> Sources { get; set; } = new()
    {
        "1337x", "TPB", "FitGirl", "DODI", "SteamRIP", "OnlineFix", "GOGGames", "RuTracker"
    };
    public string UserAgent { get; set; } =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Safari/537.36";

    public string RuTrackerUser { get; set; } = "";
    public string RuTrackerPass { get; set; } = "";

    public static string ConfigPath
    {
        get
        {
            var root = Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData),
                "GameGrab");
            Directory.CreateDirectory(root);
            return Path.Combine(root, "settings.json");
        }
    }

    public static Settings Load()
    {
        try
        {
            if (File.Exists(ConfigPath))
            {
                var json = File.ReadAllText(ConfigPath);
                var s = JsonSerializer.Deserialize<Settings>(json);
                if (s is not null) return s;
            }
        }
        catch { }
        var fresh = new Settings();
        fresh.Save();
        return fresh;
    }

    public void Save()
    {
        Directory.CreateDirectory(DownloadDir);
        var json = JsonSerializer.Serialize(this, new JsonSerializerOptions { WriteIndented = true });
        File.WriteAllText(ConfigPath, json);
    }
}
