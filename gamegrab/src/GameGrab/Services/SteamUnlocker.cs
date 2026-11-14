using System.IO;
using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;
using GameGrab.Models;

namespace GameGrab.Services;

public enum UnlockMethod
{
    AppListTxt,   // GreenLuma-style AppList\<n>.txt
    StPluginLua,  // SteamTools-style config\stplug-in\<appid>.lua
    Both,
}

public sealed class UnlockedLibrary
{
    public Dictionary<int, string> Apps { get; set; } = new();  // appid -> name
    public UnlockMethod Method { get; set; } = UnlockMethod.Both;
    public string? SteamPathOverride { get; set; }

    public static string Path => System.IO.Path.Combine(Settings.Root, "library.json");

    public static UnlockedLibrary Load()
    {
        try
        {
            if (File.Exists(Path))
            {
                var l = JsonSerializer.Deserialize<UnlockedLibrary>(File.ReadAllText(Path));
                if (l is not null) return l;
            }
        }
        catch { }
        return new UnlockedLibrary();
    }

    public void Save()
    {
        File.WriteAllText(Path, JsonSerializer.Serialize(this,
            new JsonSerializerOptions { WriteIndented = true }));
    }
}

public sealed class SteamUnlocker
{
    private readonly UnlockedLibrary _lib;

    public SteamUnlocker(UnlockedLibrary lib) => _lib = lib;

    public UnlockedLibrary Library => _lib;

    public SteamInstall? Locate() => SteamLocator.Locate(_lib.SteamPathOverride);

    public (bool ok, string message) Status()
    {
        var s = Locate();
        if (s is null) return (false, "Steam install not found (set path in Settings)");
        var greenluma = File.Exists(Path.Combine(s.Root, "GreenLuma_2024_x64.dll")) ||
                        File.Exists(Path.Combine(s.Root, "GreenLuma_2023_x86.dll")) ||
                        File.Exists(Path.Combine(s.Root, "x64launcher.exe.backup")) ||
                        File.Exists(Path.Combine(s.Root, "User32.dll")) ||
                        File.Exists(Path.Combine(s.Root, "DLLInjector.exe"));
        var steamtools = Directory.Exists(s.StPluginDir) &&
                         (File.Exists(Path.Combine(s.Root, "hid.dll")) ||
                          File.Exists(Path.Combine(s.Root, "user32.dll")));
        if (greenluma && steamtools) return (true, "GreenLuma + SteamTools detected");
        if (greenluma) return (true, "GreenLuma loader detected");
        if (steamtools) return (true, "SteamTools loader detected");
        return (false, "no loader detected — install GreenLuma or SteamTools alongside Steam");
    }

    public void AddApp(SteamApp app)
    {
        _lib.Apps[app.AppId] = app.Name;
        _lib.Save();
        app.Installed = true;
        Apply();
    }

    public void RemoveApp(int appId)
    {
        if (!_lib.Apps.Remove(appId)) return;
        _lib.Save();
        Apply();
    }

    public void Apply()
    {
        var s = Locate();
        if (s is null) return;

        if (_lib.Method is UnlockMethod.AppListTxt or UnlockMethod.Both)
            WriteAppList(s);
        if (_lib.Method is UnlockMethod.StPluginLua or UnlockMethod.Both)
            WriteStPluginLua(s);
    }

    private void WriteAppList(SteamInstall s)
    {
        Directory.CreateDirectory(s.AppListDir);
        // wipe existing numbered .txt files
        foreach (var f in Directory.EnumerateFiles(s.AppListDir, "*.txt"))
        {
            if (Regex.IsMatch(Path.GetFileNameWithoutExtension(f), @"^\d+$"))
                try { File.Delete(f); } catch { }
        }
        int i = 0;
        foreach (var kv in _lib.Apps.OrderBy(k => k.Key))
        {
            File.WriteAllText(Path.Combine(s.AppListDir, $"{i}.txt"), kv.Key.ToString());
            i++;
        }
    }

    private void WriteStPluginLua(SteamInstall s)
    {
        Directory.CreateDirectory(s.StPluginDir);
        // remove previously-written scripts to keep in sync
        foreach (var f in Directory.EnumerateFiles(s.StPluginDir, "*.lua"))
        {
            var head = SafeReadHead(f, 128);
            if (head.Contains("-- GameGrab", StringComparison.Ordinal))
                try { File.Delete(f); } catch { }
        }

        foreach (var kv in _lib.Apps)
        {
            var sb = new StringBuilder();
            sb.AppendLine("-- GameGrab");
            sb.AppendLine($"-- {kv.Value}");
            sb.AppendLine($"addappid({kv.Key})");
            File.WriteAllText(Path.Combine(s.StPluginDir, $"{kv.Key}.lua"), sb.ToString());
        }
    }

    private static string SafeReadHead(string path, int max)
    {
        try
        {
            using var fs = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite);
            var buf = new byte[Math.Min(max, (int)fs.Length)];
            _ = fs.Read(buf, 0, buf.Length);
            return Encoding.UTF8.GetString(buf);
        }
        catch { return ""; }
    }
}
