using System.Security.Cryptography;
using System.Text.Json;
using System.Text.Json.Serialization;

namespace Jellyfin.Plugin.MediaCenter.Api;

/// <summary>One branding file the server has.</summary>
public class AssetInfo
{
    [JsonPropertyName("Name")] public string Name { get; set; } = string.Empty;

    [JsonPropertyName("ContentType")] public string ContentType { get; set; } = string.Empty;

    [JsonPropertyName("Size")] public long Size { get; set; }

    /// <summary>Changes whenever the file does, so devices only fetch it again when it's new.</summary>
    [JsonPropertyName("Hash")] public string Hash { get; set; } = string.Empty;
}

/// <summary>What a branding file may be.</summary>
public sealed class AssetKind
{
    public AssetKind(string description, long maxBytes, params string[] types)
    {
        Description = description;
        MaxBytes = maxBytes;
        Types = types;
    }

    public string Description { get; }

    public long MaxBytes { get; }

    public string[] Types { get; }

    public bool Accepts(string contentType) => Types.Contains(contentType, StringComparer.OrdinalIgnoreCase);
}

/// <summary>
/// The branding files, kept in the plugin's data folder: the startup chime, the interface sounds,
/// the logo and the backdrop. Each is stored with a small description (type, size, hash).
/// </summary>
public static class AssetStore
{
    public const long MaxUploadBytes = 12 * 1024 * 1024;

    private static readonly string[] Sounds = ["audio/ogg", "audio/mpeg", "audio/wav", "audio/x-wav", "audio/wave", "audio/mp4", "audio/aac"];
    private static readonly string[] Pictures = ["image/png", "image/jpeg", "image/webp"];

    private static readonly Dictionary<string, AssetKind> Kinds = new(StringComparer.Ordinal)
    {
        ["intro"] = new AssetKind("a sound (OGG, MP3, WAV, M4A), up to 6 MB", 6 * 1024 * 1024, Sounds),
        ["focus"] = new AssetKind("a sound (OGG, MP3, WAV, M4A), up to 1 MB", 1024 * 1024, Sounds),
        ["select"] = new AssetKind("a sound (OGG, MP3, WAV, M4A), up to 1 MB", 1024 * 1024, Sounds),
        ["back"] = new AssetKind("a sound (OGG, MP3, WAV, M4A), up to 1 MB", 1024 * 1024, Sounds),
        ["error"] = new AssetKind("a sound (OGG, MP3, WAV, M4A), up to 1 MB", 1024 * 1024, Sounds),
        ["logo"] = new AssetKind("a picture (PNG, JPEG, WebP), up to 3 MB", 3 * 1024 * 1024, Pictures),
        ["backdrop"] = new AssetKind("a picture (PNG, JPEG, WebP), up to 10 MB", 10 * 1024 * 1024, Pictures),
    };

    private static readonly object Gate = new();

    public static AssetKind? KindOf(string name) => Kinds.GetValueOrDefault(name);

    private static string? Folder => Plugin.Instance?.AssetsFolder;

    public static string? PathOf(string name) => KindOf(name) is null || Folder is null ? null : Path.Combine(Folder, name + ".bin");

    private static string? InfoPathOf(string name) => KindOf(name) is null || Folder is null ? null : Path.Combine(Folder, name + ".json");

    public static AssetInfo? Find(string name)
    {
        var infoPath = InfoPathOf(name);
        if (infoPath is null || !File.Exists(infoPath))
        {
            return null;
        }

        try
        {
            return JsonSerializer.Deserialize<AssetInfo>(File.ReadAllText(infoPath));
        }
        catch (JsonException)
        {
            return null;
        }
    }

    public static IEnumerable<AssetInfo> List() => Kinds.Keys.Select(Find).OfType<AssetInfo>();

    public static AssetInfo Save(string name, string contentType, byte[] data)
    {
        var info = new AssetInfo
        {
            Name = name,
            ContentType = contentType,
            Size = data.LongLength,
            Hash = Convert.ToHexString(SHA256.HashData(data)).ToLowerInvariant()[..16],
        };
        lock (Gate)
        {
            Directory.CreateDirectory(Folder!);
            File.WriteAllBytes(PathOf(name)!, data);
            File.WriteAllText(InfoPathOf(name)!, JsonSerializer.Serialize(info));
        }

        return info;
    }

    public static void Delete(string name)
    {
        lock (Gate)
        {
            foreach (var path in new[] { PathOf(name), InfoPathOf(name) })
            {
                if (path is not null && File.Exists(path))
                {
                    File.Delete(path);
                }
            }
        }
    }
}
