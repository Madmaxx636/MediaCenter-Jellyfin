using System.Globalization;
using System.Net.Mime;
using System.Text.Json;
using System.Text.Json.Serialization;
using Jellyfin.Plugin.MediaCenter.Configuration;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Mvc;

namespace Jellyfin.Plugin.MediaCenter.Api;

/// <summary>What the app gets from the server for the signed-in user.</summary>
public class ClientConfig
{
    [JsonPropertyName("PluginVersion")] public string PluginVersion { get; set; } = string.Empty;

    [JsonPropertyName("Settings")] public SettingValue[] Settings { get; set; } = [];

    [JsonPropertyName("Notices")] public NoticeDto[] Notices { get; set; } = [];

    [JsonPropertyName("Update")] public UpdateInfo? Update { get; set; }

    [JsonPropertyName("Branding")] public BrandingDto Branding { get; set; } = new();

    /// <summary>The app version whose settings list the server has, so a newer app knows to send its own.</summary>
    [JsonPropertyName("CatalogVersion")] public string CatalogVersion { get; set; } = string.Empty;
}

public class NoticeDto
{
    [JsonPropertyName("Id")] public string Id { get; set; } = string.Empty;

    [JsonPropertyName("Title")] public string Title { get; set; } = string.Empty;

    [JsonPropertyName("Text")] public string Text { get; set; } = string.Empty;
}

public class UpdateInfo
{
    [JsonPropertyName("Version")] public string Version { get; set; } = string.Empty;

    [JsonPropertyName("Url")] public string Url { get; set; } = string.Empty;

    [JsonPropertyName("Notes")] public string Notes { get; set; } = string.Empty;
}

public class BrandingDto
{
    [JsonPropertyName("IntroTitle")] public string IntroTitle { get; set; } = string.Empty;

    [JsonPropertyName("IntroSubtitle")] public string IntroSubtitle { get; set; } = string.Empty;

    [JsonPropertyName("AccentColor")] public string AccentColor { get; set; } = string.Empty;

    [JsonPropertyName("Assets")] public AssetInfo[] Assets { get; set; } = [];
}

/// <summary>
/// The Media Center app's side of the server: its settings for the signed-in user, notices, update
/// notices and branding; and, for administrators, the branding files.
/// </summary>
[ApiController]
[Route("MediaCenter")]
[Authorize]
public class MediaCenterController : ControllerBase
{
    private const string UserIdClaim = "Jellyfin-UserId";
    private const string AdminPolicy = "RequiresElevation";

    private readonly UpdateCheck _updates;

    public MediaCenterController(IHttpClientFactory httpClientFactory)
    {
        _updates = UpdateCheck.For(httpClientFactory);
    }

    private static PluginConfiguration Config => Plugin.Instance?.Configuration ?? new PluginConfiguration();

    private string UserId => NormalizeId(User.FindFirst(UserIdClaim)?.Value);

    private static string NormalizeId(string? id) => (id ?? string.Empty).Replace("-", string.Empty, StringComparison.Ordinal).ToLowerInvariant();

    /// <summary>Everything the app takes from the server, for the signed-in user.</summary>
    [HttpGet("Client")]
    [Produces(MediaTypeNames.Application.Json)]
    public async Task<ActionResult<ClientConfig>> GetClient()
    {
        var config = Config;
        var user = UserId;

        // The defaults, then this user's own on top; a setting locked in either stays locked.
        var settings = new Dictionary<string, SettingValue>(StringComparer.Ordinal);
        foreach (var s in config.Defaults.Where(s => s.Key.Length > 0))
        {
            settings[s.Key] = new SettingValue { Key = s.Key, Value = s.Value, Locked = s.Locked };
        }

        var mine = config.Users.FirstOrDefault(u => NormalizeId(u.UserId) == user);
        foreach (var s in mine?.Settings.Where(s => s.Key.Length > 0) ?? [])
        {
            var lockedBefore = settings.TryGetValue(s.Key, out var before) && before.Locked;
            settings[s.Key] = new SettingValue { Key = s.Key, Value = s.Value, Locked = s.Locked || lockedBefore };
        }

        var now = DateTimeOffset.UtcNow;
        var notices = config.Notices
            .Where(n => n.Enabled && (n.Text.Length > 0 || n.Title.Length > 0))
            .Where(n => n.UserIds.Length == 0 || n.UserIds.Any(id => NormalizeId(id) == user))
            .Where(n => !TryDate(n.StartsAt, out var from) || from <= now)
            .Where(n => !TryDate(n.EndsAt, out var until) || now <= until)
            .Select(n => new NoticeDto { Id = n.Id, Title = n.Title, Text = n.Text })
            .ToArray();

        return new ClientConfig
        {
            PluginVersion = Plugin.Instance?.Version.ToString() ?? string.Empty,
            Settings = settings.Values.ToArray(),
            Notices = notices,
            Update = config.UpdateNotices ? await _updates.LatestAsync().ConfigureAwait(false) : null,
            Branding = new BrandingDto
            {
                IntroTitle = config.IntroTitle,
                IntroSubtitle = config.IntroSubtitle,
                AccentColor = config.AccentColor,
                Assets = AssetStore.List().ToArray(),
            },
            CatalogVersion = config.SettingsCatalogVersion,
        };
    }

    /// <summary>
    /// The app reports its list of settings (keys, names, help, choices), so the settings page here
    /// offers exactly what the app has. Kept from the newest app version that connects.
    /// </summary>
    [HttpPost("Catalog")]
    public async Task<ActionResult> PostCatalog()
    {
        using var reader = new StreamReader(Request.Body);
        var text = await reader.ReadToEndAsync().ConfigureAwait(false);
        if (text.Length > 256 * 1024)
        {
            return BadRequest("Too large");
        }

        string version;
        try
        {
            using var doc = JsonDocument.Parse(text);
            version = doc.RootElement.GetProperty("AppVersion").GetString() ?? string.Empty;
            if (doc.RootElement.GetProperty("Settings").ValueKind != JsonValueKind.Array)
            {
                return BadRequest("Settings must be a list");
            }
        }
        catch (Exception e) when (e is JsonException or KeyNotFoundException or InvalidOperationException)
        {
            return BadRequest("Not a settings list");
        }

        var plugin = Plugin.Instance;
        if (plugin is null)
        {
            return NoContent();
        }

        var current = plugin.Configuration.SettingsCatalogVersion;
        if (!Version.TryParse(Plain(current), out _) || IsNewer(version, current) || Plain(version) == Plain(current))
        {
            plugin.Configuration.SettingsCatalog = text;
            plugin.Configuration.SettingsCatalogVersion = version;
            plugin.SaveConfiguration();
        }

        return NoContent();
    }

    /// <summary>One branding file (a sound, the logo, the backdrop).</summary>
    [HttpGet("Assets/{name}")]
    public ActionResult GetAsset([FromRoute] string name)
    {
        var info = AssetStore.Find(name);
        var path = AssetStore.PathOf(name);
        if (info is null || path is null || !System.IO.File.Exists(path))
        {
            return NotFound();
        }

        Response.Headers.ETag = "\"" + info.Hash + "\"";
        return PhysicalFile(path, info.ContentType);
    }

    /// <summary>The branding files there are (administrators).</summary>
    [HttpGet("Assets")]
    [Authorize(Policy = AdminPolicy)]
    [System.Diagnostics.CodeAnalysis.SuppressMessage("Performance", "CA1822", Justification = "ASP.NET actions are instance methods.")]
    public ActionResult<AssetInfo[]> ListAssets() => AssetStore.List().ToArray();

    /// <summary>Uploads a branding file: the raw file as the body, with its content type (administrators).</summary>
    [HttpPost("Assets/{name}")]
    [Authorize(Policy = AdminPolicy)]
    [RequestSizeLimit(AssetStore.MaxUploadBytes)]
    public async Task<ActionResult<AssetInfo>> PutAsset([FromRoute] string name)
    {
        var kind = AssetStore.KindOf(name);
        if (kind is null)
        {
            return NotFound("No such branding file");
        }

        var type = (Request.ContentType ?? string.Empty).Split(';')[0].Trim().ToLowerInvariant();
        if (!kind.Accepts(type))
        {
            return BadRequest($"{name} must be {kind.Description}");
        }

        using var buffer = new MemoryStream();
        await Request.Body.CopyToAsync(buffer).ConfigureAwait(false);
        if (buffer.Length == 0 || buffer.Length > kind.MaxBytes)
        {
            return BadRequest($"{name} must be at most {kind.MaxBytes / (1024 * 1024)} MB");
        }

        return AssetStore.Save(name, type, buffer.ToArray());
    }

    /// <summary>Removes a branding file, so devices go back to the app's own (administrators).</summary>
    [HttpDelete("Assets/{name}")]
    [Authorize(Policy = AdminPolicy)]
    public ActionResult DeleteAsset([FromRoute] string name)
    {
        if (AssetStore.KindOf(name) is null)
        {
            return NotFound();
        }

        AssetStore.Delete(name);
        return NoContent();
    }

    private static bool TryDate(string text, out DateTimeOffset value) =>
        DateTimeOffset.TryParse(text, CultureInfo.InvariantCulture, DateTimeStyles.AssumeUniversal, out value) && text.Length > 0;

    internal static bool IsNewer(string candidate, string than) =>
        Version.TryParse(Plain(candidate), out var a) && Version.TryParse(Plain(than), out var b) && a > b;

    /// <summary>A version without its "v" or any suffix ("v0.9.8-qa" is 0.9.8).</summary>
    internal static string Plain(string version) => version.TrimStart('v').Split('-')[0];
}
