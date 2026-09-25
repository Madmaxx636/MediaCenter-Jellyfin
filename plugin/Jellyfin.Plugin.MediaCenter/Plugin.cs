using Jellyfin.Plugin.MediaCenter.Configuration;
using MediaBrowser.Common.Configuration;
using MediaBrowser.Common.Plugins;
using MediaBrowser.Model.Plugins;
using MediaBrowser.Model.Serialization;

namespace Jellyfin.Plugin.MediaCenter;

/// <summary>
/// Media Center for Jellyfin, on the server: settings the Media Center app on Android TV takes
/// from the server (defaults, locks, per user), notices for its start menu, update notices, and
/// the server's own branding (startup chime and sounds, logo, intro title, backdrop and colour).
/// </summary>
public class Plugin : BasePlugin<PluginConfiguration>, IHasWebPages
{
    public Plugin(IApplicationPaths applicationPaths, IXmlSerializer xmlSerializer)
        : base(applicationPaths, xmlSerializer)
    {
        Instance = this;
    }

    public static Plugin? Instance { get; private set; }

    public override string Name => "Media Center";

    public override Guid Id => Guid.Parse("9167cfe0-b0f6-4832-8848-0c9d3e677bc7");

    public override string Description =>
        "Control and brand the Media Center app for Android TV: default and locked settings, notices, update notices, sounds, logo and backdrop.";

    /// <summary>Where uploaded branding files are kept.</summary>
    public string AssetsFolder => Path.Combine(DataFolderPath, "assets");

    public IEnumerable<PluginPageInfo> GetPages() =>
    [
        new PluginPageInfo
        {
            Name = "MediaCenter",
            DisplayName = "Media Center",
            EmbeddedResourcePath = GetType().Namespace + ".Configuration.configPage.html",
            EnableInMainMenu = true,
            MenuIcon = "tv",
        },
    ];
}
