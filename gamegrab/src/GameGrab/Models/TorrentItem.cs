using System.ComponentModel;
using System.Runtime.CompilerServices;

namespace GameGrab.Models;

public enum TorrentState { Queued, Metadata, Downloading, Seeding, Paused, Finished, Error }

public sealed class TorrentItem : INotifyPropertyChanged
{
    private string _name = "";
    private TorrentState _state;
    private double _progress;
    private long _size;
    private long _downloaded;
    private long _downSpeed;
    private long _upSpeed;
    private int _peers;
    private int _seeds;
    private string _savePath = "";
    private string? _hash;
    private string? _error;
    private string _eta = "";

    public string Name { get => _name; set => Set(ref _name, value); }
    public TorrentState State { get => _state; set => Set(ref _state, value); }
    public double Progress { get => _progress; set => Set(ref _progress, value); }
    public long Size { get => _size; set => Set(ref _size, value); }
    public long Downloaded { get => _downloaded; set => Set(ref _downloaded, value); }
    public long DownSpeed { get => _downSpeed; set => Set(ref _downSpeed, value); }
    public long UpSpeed { get => _upSpeed; set => Set(ref _upSpeed, value); }
    public int Peers { get => _peers; set => Set(ref _peers, value); }
    public int Seeds { get => _seeds; set => Set(ref _seeds, value); }
    public string SavePath { get => _savePath; set => Set(ref _savePath, value); }
    public string? Hash { get => _hash; set => Set(ref _hash, value); }
    public string? Error { get => _error; set => Set(ref _error, value); }
    public string Eta { get => _eta; set => Set(ref _eta, value); }
    public string Source { get; init; } = "";

    public event PropertyChangedEventHandler? PropertyChanged;

    private void Set<T>(ref T field, T value, [CallerMemberName] string? prop = null)
    {
        if (EqualityComparer<T>.Default.Equals(field, value)) return;
        field = value;
        PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(prop));
    }
}
