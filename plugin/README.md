# Media Center plugin for Jellyfin

The server side of [Media Center for Jellyfin](../README.md). With it, a Jellyfin administrator can:

- **Set the app's settings** for every device, or for particular users, and **lock** any of them
  so they can't be changed on the TV. The settings page lists exactly the settings the app has
  (the app sends its list when it connects).
- **Show notices** on the app's start menu, for everyone or chosen users, optionally between two dates.
- **Tell devices about app updates**: the server checks GitHub for new releases and the app shows
  where to get the new version.
- **Brand the app**: your own startup chime and interface sounds, logo, intro title, backdrop
  picture and accent colour. Only upload files you have the right to use.

Devices pick up changes when the app starts or when they go back to the start menu (at most every
ten minutes). Servers without the plugin are unaffected.

Needs Jellyfin 12.1 or later and Media Center 0.9.8 or later on the TVs.

## Install

**From the plugin repository** (Dashboard → Plugins → Repositories → +):

```
https://raw.githubusercontent.com/Madmaxx636/MediaCenter-Jellyfin/main/plugin/manifest.json
```

Then Dashboard → Plugins → Catalog → **Media Center** → Install, and restart the server.

**By hand:** unzip `jellyfin-plugin-mediacenter_<version>.zip` from
[`plugin/repo`](repo) into a folder named
`MediaCenter_<version>` inside the server's `plugins` folder, and restart the server.

The settings are under Dashboard → Plugins → **Media Center** (also in the dashboard's menu).

## Build

Needs the .NET 10 SDK.

```bash
plugin/build.sh "What changed"
```

This builds `plugin/repo/jellyfin-plugin-mediacenter_<version>.zip` and adds the version to
`plugin/manifest.json` with its checksum. The version is `AssemblyVersion` in the `.csproj`. Commit
both and push: the repository address above then offers the new version.

## What the app asks for

| Address | Who | What |
|---|---|---|
| `GET /MediaCenter/Client` | any signed-in user | settings (with locks) for this user, notices, newest app version, branding |
| `POST /MediaCenter/Catalog` | any signed-in user | the app's list of settings, for the settings page |
| `GET /MediaCenter/Assets/{name}` | any signed-in user | a branding file: `intro`, `focus`, `select`, `back`, `error`, `logo`, `backdrop` |
| `GET/POST/DELETE /MediaCenter/Assets[/{name}]` | administrators | list, upload (raw file with its content type) and remove branding files |
