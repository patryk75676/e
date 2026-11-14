using System.Net.Http;
using GameGrab.Models;
using GameGrab.Services;

namespace GameGrab.Sources;

public interface ISource
{
    string Name { get; }
    Task<IReadOnlyList<SearchResult>> SearchAsync(string query, Settings settings, HttpClient http, CancellationToken ct);
    Task<SearchResult> ResolveAsync(SearchResult result, Settings settings, HttpClient http, CancellationToken ct);
}
