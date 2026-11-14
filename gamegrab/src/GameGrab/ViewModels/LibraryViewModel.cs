using System.Collections.ObjectModel;
using System.Windows;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using GameGrab.Models;
using GameGrab.Services;

namespace GameGrab.ViewModels;

public sealed partial class LibraryViewModel : ObservableObject
{
    private readonly IServiceHost _host;

    public ObservableCollection<SteamApp> Results { get; } = new();
    public ObservableCollection<SteamApp> Installed { get; } = new();

    [ObservableProperty] private string _query = "";
    [ObservableProperty] private string _status = "";
    [ObservableProperty] private bool _searching;
    [ObservableProperty] private bool _loaderOk;
    [ObservableProperty] private string _loaderMessage = "";
    [ObservableProperty] private SteamApp? _selected;
    [ObservableProperty] private string _steamPath = "";

    public IReadOnlyList<UnlockMethod> Methods { get; } = new[]
    { UnlockMethod.Both, UnlockMethod.AppListTxt, UnlockMethod.StPluginLua };

    public LibraryViewModel(IServiceHost host)
    {
        _host = host;
        Refresh();
        foreach (var kv in _host.SteamLib.Library.Apps)
            Installed.Add(new SteamApp { AppId = kv.Key, Name = kv.Value,
                HeaderUrl = $"https://cdn.cloudflare.steamstatic.com/steam/apps/{kv.Key}/header.jpg",
                CapsuleUrl = $"https://cdn.cloudflare.steamstatic.com/steam/apps/{kv.Key}/capsule_231x87.jpg",
                IconUrl = $"https://cdn.cloudflare.steamstatic.com/steam/apps/{kv.Key}/capsule_sm_120.jpg",
                Installed = true });
    }

    public UnlockMethod Method
    {
        get => _host.SteamLib.Library.Method;
        set { _host.SteamLib.Library.Method = value; _host.SteamLib.Library.Save(); _host.SteamLib.Apply(); OnPropertyChanged(); }
    }

    private void Refresh()
    {
        var (ok, msg) = _host.SteamLib.Status();
        LoaderOk = ok;
        LoaderMessage = msg;
        SteamPath = _host.SteamLib.Locate()?.Root ?? _host.SteamLib.Library.SteamPathOverride ?? "";
    }

    [RelayCommand]
    private async Task SearchAsync()
    {
        if (string.IsNullOrWhiteSpace(Query)) return;
        Searching = true;
        Status = $"searching Steam for '{Query}'";
        try
        {
            Results.Clear();
            var list = await _host.SteamApi.SearchAsync(Query);
            foreach (var r in list)
            {
                r.Installed = _host.SteamLib.Library.Apps.ContainsKey(r.AppId);
                Results.Add(r);
            }
            Status = $"{Results.Count} apps";
        }
        catch (Exception ex) { Status = ex.Message; }
        finally { Searching = false; }
    }

    [RelayCommand]
    private void AddSelected()
    {
        if (Selected is null) return;
        _host.SteamLib.AddApp(Selected);
        if (!Installed.Any(a => a.AppId == Selected.AppId))
            Installed.Add(new SteamApp
            {
                AppId = Selected.AppId, Name = Selected.Name,
                HeaderUrl = Selected.HeaderUrl, CapsuleUrl = Selected.CapsuleUrl,
                IconUrl = Selected.IconUrl, Installed = true,
            });
        Status = $"added {Selected.Name} ({Selected.AppId}) — restart Steam to see it";
    }

    [RelayCommand]
    private void RemoveSelectedInstalled(SteamApp? app)
    {
        if (app is null) return;
        _host.SteamLib.RemoveApp(app.AppId);
        var found = Installed.FirstOrDefault(a => a.AppId == app.AppId);
        if (found is not null) Installed.Remove(found);
        var r = Results.FirstOrDefault(a => a.AppId == app.AppId);
        if (r is not null) r.Installed = false;
        Status = $"removed {app.Name}";
    }

    [RelayCommand]
    private void BrowseSteam()
    {
        var dlg = new Microsoft.Win32.OpenFolderDialog { Title = "Pick Steam install folder" };
        if (dlg.ShowDialog() == true)
        {
            _host.SteamLib.Library.SteamPathOverride = dlg.FolderName;
            _host.SteamLib.Library.Save();
            Refresh();
        }
    }

    [RelayCommand]
    private void ReapplyAll()
    {
        _host.SteamLib.Apply();
        Refresh();
        Status = "library files re-written. restart Steam to refresh.";
    }

    [RelayCommand]
    private void AddByAppId()
    {
        if (!int.TryParse(Query, out var id)) { Status = "type a numeric AppID or search above"; return; }
        var app = new SteamApp
        {
            AppId = id,
            Name = $"appid-{id}",
            HeaderUrl = $"https://cdn.cloudflare.steamstatic.com/steam/apps/{id}/header.jpg",
            CapsuleUrl = $"https://cdn.cloudflare.steamstatic.com/steam/apps/{id}/capsule_231x87.jpg",
            IconUrl = $"https://cdn.cloudflare.steamstatic.com/steam/apps/{id}/capsule_sm_120.jpg",
        };
        _host.SteamLib.AddApp(app);
        if (!Installed.Any(a => a.AppId == id)) Installed.Add(app);
        Status = $"added appid {id} — restart Steam";
    }

    [RelayCommand]
    private void RestartSteam()
    {
        try
        {
            foreach (var p in System.Diagnostics.Process.GetProcessesByName("steam"))
            { try { p.Kill(true); } catch { } }
            var exe = System.IO.Path.Combine(SteamPath, "steam.exe");
            if (System.IO.File.Exists(exe))
                System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo(exe) { UseShellExecute = true });
            Status = "Steam restarted";
        }
        catch (Exception ex) { Status = ex.Message; }
    }
}
