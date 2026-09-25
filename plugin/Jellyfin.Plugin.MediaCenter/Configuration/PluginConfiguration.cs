using MediaBrowser.Model.Plugins;

namespace Jellyfin.Plugin.MediaCenter.Configuration;

/// <summary>One of the app's settings, as the server sets it.</summary>
public class SettingValue
{
    /// <summary>The app's key for the setting (as in the settings list the app reports).</summary>
    public string Key { get; set; } = string.Empty;

    /// <summary>The value, as text (true/false for switches).</summary>
    public string Value { get; set; } = string.Empty;

    /// <summary>When set, users can't change it on their devices.</summary>
    public bool Locked { get; set; }
}

/// <summary>Settings for one Jellyfin user, on top of the defaults for everyone.</summary>
public class UserSettings
{
    public string UserId { get; set; } = string.Empty;

    public string UserName { get; set; } = string.Empty;

    public SettingValue[] Settings { get; set; } = [];
}

/// <summary>A notice shown on the app's start menu.</summary>
public class Notice
{
    public string Id { get; set; } = Guid.NewGuid().ToString("N");

    public string Title { get; set; } = string.Empty;

    public string Text { get; set; } = string.Empty;

    public bool Enabled { get; set; } = true;

    /// <summary>Optional start and end (ISO 8601); empty for always.</summary>
    public string StartsAt { get; set; } = string.Empty;

    public string EndsAt { get; set; } = string.Empty;

    /// <summary>Who sees it; empty for everyone.</summary>
    public string[] UserIds { get; set; } = [];
}

public class PluginConfiguration : BasePluginConfiguration
{
    /// <summary>Settings for every device and user.</summary>
    public SettingValue[] Defaults { get; set; } = [];

    /// <summary>Settings for particular users, over the defaults.</summary>
    public UserSettings[] Users { get; set; } = [];

    public Notice[] Notices { get; set; } = [];

    /// <summary>Tell devices when a newer version of the app is on GitHub.</summary>
    public bool UpdateNotices { get; set; } = true;

    /// <summary>The intro's title and the smaller words after it; empty keeps the app's own.</summary>
    public string IntroTitle { get; set; } = string.Empty;

    public string IntroSubtitle { get; set; } = string.Empty;

    /// <summary>Accent colour as #RRGGBB; empty keeps the app's own.</summary>
    public string AccentColor { get; set; } = string.Empty;

    /// <summary>
    /// The app's list of settings (keys, names, choices), as the newest app to connect reported it,
    /// so this page always offers exactly the settings the app has.
    /// </summary>
    public string SettingsCatalog { get; set; } = string.Empty;

    public string SettingsCatalogVersion { get; set; } = string.Empty;
}
