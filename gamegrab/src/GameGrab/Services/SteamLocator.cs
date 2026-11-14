using System.IO;
using Microsoft.Win32;

namespace GameGrab.Services;

public sealed record SteamInstall(string Root, string AppListDir, string StPluginDir, string ConfigVdfPath);

public static class SteamLocator
{
    public static SteamInstall? Locate(string? overrideRoot = null)
    {
        var root = overrideRoot;
        if (string.IsNullOrEmpty(root))
            root = TryRegistry("HKEY_CURRENT_USER\\Software\\Valve\\Steam", "SteamPath")
                 ?? TryRegistry("HKEY_LOCAL_MACHINE\\SOFTWARE\\WOW6432Node\\Valve\\Steam", "InstallPath")
                 ?? TryRegistry("HKEY_LOCAL_MACHINE\\SOFTWARE\\Valve\\Steam", "InstallPath");

        if (string.IsNullOrEmpty(root) || !Directory.Exists(root))
            return null;

        root = root.Replace('/', Path.DirectorySeparatorChar);
        return new SteamInstall(
            Root: root,
            AppListDir: Path.Combine(root, "AppList"),
            StPluginDir: Path.Combine(root, "config", "stplug-in"),
            ConfigVdfPath: Path.Combine(root, "config", "config.vdf")
        );
    }

    private static string? TryRegistry(string path, string name)
    {
        try { return Registry.GetValue(path, name, null) as string; }
        catch { return null; }
    }
}
