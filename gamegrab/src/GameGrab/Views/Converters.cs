using System.Globalization;
using System.Windows.Data;
using System.Windows.Media;
using GameGrab.Models;

namespace GameGrab.Views;

public sealed class BytesConverter : IValueConverter
{
    public object Convert(object value, Type targetType, object? parameter, CultureInfo culture)
    {
        if (value is null) return "";
        long b = System.Convert.ToInt64(value);
        string[] u = { "B", "KB", "MB", "GB", "TB" };
        double f = b;
        int i = 0;
        while (f >= 1024 && i < u.Length - 1) { f /= 1024; i++; }
        return $"{f:0.##} {u[i]}";
    }
    public object ConvertBack(object value, Type targetType, object? parameter, CultureInfo culture) => Binding.DoNothing;
}

public sealed class RateConverter : IValueConverter
{
    public object Convert(object value, Type targetType, object? parameter, CultureInfo culture)
    {
        long b = value is null ? 0 : System.Convert.ToInt64(value);
        string[] u = { "B/s", "KB/s", "MB/s", "GB/s" };
        double f = b;
        int i = 0;
        while (f >= 1024 && i < u.Length - 1) { f /= 1024; i++; }
        return $"{f:0.#} {u[i]}";
    }
    public object ConvertBack(object value, Type targetType, object? parameter, CultureInfo culture) => Binding.DoNothing;
}

public sealed class BoolToVisConverter : IValueConverter
{
    public object Convert(object value, Type targetType, object? parameter, CultureInfo culture)
        => (value is bool b && b) ? System.Windows.Visibility.Visible : System.Windows.Visibility.Collapsed;
    public object ConvertBack(object value, Type targetType, object? parameter, CultureInfo culture) => Binding.DoNothing;
}

public sealed class BoolToSeverityConverter : IValueConverter
{
    public object Convert(object value, Type targetType, object? parameter, CultureInfo culture)
        => (value is bool b && b) ? Wpf.Ui.Controls.InfoBarSeverity.Success : Wpf.Ui.Controls.InfoBarSeverity.Warning;
    public object ConvertBack(object value, Type targetType, object? parameter, CultureInfo culture) => Binding.DoNothing;
}

public sealed class StateToColorConverter : IValueConverter
{
    public object Convert(object value, Type targetType, object? parameter, CultureInfo culture)
    {
        var hex = value is TorrentState s ? s switch
        {
            TorrentState.Downloading => "#3CB371",
            TorrentState.Seeding => "#4FC3F7",
            TorrentState.Finished => "#90EE90",
            TorrentState.Paused => "#FFB74D",
            TorrentState.Error => "#EF5350",
            _ => "#B0BEC5",
        } : "#888888";
        var brush = new SolidColorBrush((Color)ColorConverter.ConvertFromString(hex));
        brush.Freeze();
        return brush;
    }
    public object ConvertBack(object value, Type targetType, object? parameter, CultureInfo culture) => Binding.DoNothing;
}
