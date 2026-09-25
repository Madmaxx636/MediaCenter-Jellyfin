using System.Text.Json;

namespace Jellyfin.Plugin.MediaCenter.Api;

/// <summary>
/// The newest release of the app on GitHub, looked up at most every six hours and shared by every
/// device, so the server (not each TV) asks GitHub, and quietly does without if it can't.
/// </summary>
public sealed class UpdateCheck
{
    // Every release, not just the "latest": GitHub's latest leaves out pre-releases, and the app's are marked so.
    private const string Releases = "https://api.github.com/repos/Madmaxx636/MediaCenter-Jellyfin/releases?per_page=20";
    private static readonly TimeSpan KeepFor = TimeSpan.FromHours(6);
    private static readonly SemaphoreSlim Gate = new(1, 1);
    private static UpdateCheck? _shared;

    private readonly IHttpClientFactory _httpClientFactory;
    private UpdateInfo? _latest;
    private DateTimeOffset _checkedAt = DateTimeOffset.MinValue;

    private UpdateCheck(IHttpClientFactory httpClientFactory)
    {
        _httpClientFactory = httpClientFactory;
    }

    public static UpdateCheck For(IHttpClientFactory httpClientFactory) => _shared ??= new UpdateCheck(httpClientFactory);

    public async Task<UpdateInfo?> LatestAsync()
    {
        if (DateTimeOffset.UtcNow - _checkedAt < KeepFor)
        {
            return _latest;
        }

        await Gate.WaitAsync().ConfigureAwait(false);
        try
        {
            if (DateTimeOffset.UtcNow - _checkedAt < KeepFor)
            {
                return _latest;
            }

            _checkedAt = DateTimeOffset.UtcNow;
            using var client = _httpClientFactory.CreateClient();
            client.Timeout = TimeSpan.FromSeconds(10);
            client.DefaultRequestHeaders.UserAgent.ParseAdd("Jellyfin-Plugin-MediaCenter/1.0");
            client.DefaultRequestHeaders.Accept.ParseAdd("application/vnd.github+json");
            using var response = await client.GetAsync(Releases).ConfigureAwait(false);
            if (!response.IsSuccessStatusCode)
            {
                return _latest;
            }

            await using var stream = await response.Content.ReadAsStreamAsync().ConfigureAwait(false);
            using var doc = await JsonDocument.ParseAsync(stream).ConfigureAwait(false);
            // The highest version among the published (not draft) releases.
            JsonElement? newest = null;
            Version? newestVersion = null;
            foreach (var release in doc.RootElement.EnumerateArray())
            {
                if (release.TryGetProperty("draft", out var draft) && draft.GetBoolean())
                {
                    continue;
                }

                var tag = (release.GetProperty("tag_name").GetString() ?? string.Empty).TrimStart('v');
                if (Version.TryParse(tag, out var version) && (newestVersion is null || version > newestVersion))
                {
                    newest = release;
                    newestVersion = version;
                }
            }

            if (newest is not { } root)
            {
                return _latest;
            }

            var notes = root.TryGetProperty("body", out var body) ? body.GetString() ?? string.Empty : string.Empty;
            _latest = new UpdateInfo
            {
                Version = newestVersion!.ToString(),
                Url = root.GetProperty("html_url").GetString() ?? string.Empty,
                Notes = notes.Length > 600 ? notes[..600] + "…" : notes,
            };
            return _latest;
        }
        catch (Exception e) when (e is HttpRequestException or TaskCanceledException or JsonException or KeyNotFoundException or InvalidOperationException)
        {
            return _latest;
        }
        finally
        {
            Gate.Release();
        }
    }
}
