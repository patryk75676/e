using System.ComponentModel;
using System.Runtime.CompilerServices;

namespace GameGrab.Models;

public sealed class SteamApp : INotifyPropertyChanged
{
    private bool _installed;

    public required int AppId { get; init; }
    public required string Name { get; init; }
    public string HeaderUrl { get; init; } = "";
    public string CapsuleUrl { get; init; } = "";
    public string IconUrl { get; init; } = "";
    public string Developer { get; init; } = "";
    public string ReleaseDate { get; init; } = "";
    public string Type { get; init; } = "game";
    public bool Installed { get => _installed; set { _installed = value; OnChanged(); } }

    public event PropertyChangedEventHandler? PropertyChanged;
    private void OnChanged([CallerMemberName] string? p = null)
        => PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(p));
}
