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
    public AssetKind(string description, long maxBytes, bool sound)
    {
        Description = description;
        MaxBytes = maxBytes;
        Sound = sound;
    }

    public string Description { get; }

    public long MaxBytes { get; }

    public bool Sound { get; }

    /// <summary>
    /// The file's type, read from its first bytes, or null when it isn't one this kind may be.
    /// Browsers label the same file differently (Firefox calls an .ogg "video/ogg", some send
    /// nothing), so the label they send isn't trusted.
    /// </summary>
    public string? TypeOf(ReadOnlySpan<byte> data) => Sound ? SoundType(data) : PictureType(data);

    private static string? SoundType(ReadOnlySpan<byte> d)
    {
        if (d.StartsWith("OggS"u8)) return "audio/ogg";
        if (d.StartsWith("fLaC"u8)) return "audio/flac";
        if (d.StartsWith("ID3"u8)) return "audio/mpeg";
        if (d.Length >= 12 && d.StartsWith("RIFF"u8) && d[8..12].SequenceEqual("WAVE"u8)) return "audio/wav";
        if (d.Length >= 8 && d[4..8].SequenceEqual("ftyp"u8)) return "audio/mp4";
        if (d.Length >= 2 && d[0] == 0xFF && (d[1] & 0xE0) == 0xE0)
        {
            // A frame header: layer bits 00 are AAC in ADTS, anything else is MPEG audio (MP3).
            return (d[1] & 0x06) == 0 ? "audio/aac" : "audio/mpeg";
        }

        return null;
    }

    private static string? PictureType(ReadOnlySpan<byte> d)
    {
        if (d.StartsWith(new byte[] { 0x89, 0x50, 0x4E, 0x47 })) return "image/png";
        if (d.StartsWith(new byte[] { 0xFF, 0xD8, 0xFF })) return "image/jpeg";
        if (d.Length >= 12 && d.StartsWith("RIFF"u8) && d[8..12].SequenceEqual("WEBP"u8)) return "image/webp";
        return null;
    }
}

/// <summary>
/// The branding files, kept in the plugin's data folder: the startup chime, the interface sounds,
/// the logo and the backdrop. Each is stored with a small description (type, size, hash).
/// </summary>
public static class AssetStore
{
    public const long MaxUploadBytes = 12 * 1024 * 1024;

    private static readonly Dictionary<string, AssetKind> Kinds = new(StringComparer.Ordinal)
    {
        ["intro"] = new AssetKind("a sound (OGG, MP3, WAV, M4A, FLAC), up to 6 MB", 6 * 1024 * 1024, true),
        ["focus"] = new AssetKind("a sound (OGG, MP3, WAV, M4A, FLAC), up to 1 MB", 1024 * 1024, true),
        ["select"] = new AssetKind("a sound (OGG, MP3, WAV, M4A, FLAC), up to 1 MB", 1024 * 1024, true),
        ["back"] = new AssetKind("a sound (OGG, MP3, WAV, M4A, FLAC), up to 1 MB", 1024 * 1024, true),
        ["error"] = new AssetKind("a sound (OGG, MP3, WAV, M4A, FLAC), up to 1 MB", 1024 * 1024, true),
        ["logo"] = new AssetKind("a picture (PNG, JPEG, WebP), up to 3 MB", 3 * 1024 * 1024, false),
        ["backdrop"] = new AssetKind("a picture (PNG, JPEG, WebP), up to 10 MB", 10 * 1024 * 1024, false),
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
            // Written aside, then swapped in whole, so a device fetching it meanwhile never gets half a file.
            WriteWhole(PathOf(name)!, path => File.WriteAllBytes(path, data));
            WriteWhole(InfoPathOf(name)!, path => File.WriteAllText(path, JsonSerializer.Serialize(info)));
        }

        return info;
    }

    private static void WriteWhole(string path, Action<string> write)
    {
        var temporary = path + ".tmp";
        write(temporary);
        File.Move(temporary, path, overwrite: true);
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
