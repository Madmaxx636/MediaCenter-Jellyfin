# Media Center for Jellyfin

An Android TV client for [Jellyfin](https://jellyfin.org) that looks, sounds and behaves like
Windows 7 Media Center: the startup animation, the vertical start menu with its strips of tiles,
glass and glow, pivot-driven galleries, the transport strip over video, and soft interface sounds.
It plays almost anything as-is, and is built to stay smooth on small TV boxes with 2 GB of memory.

![The startup animation](docs/screenshots/intro.jpg)

| | |
|---|---|
| ![Start menu](docs/screenshots/start-menu.jpg) | ![Music strip](docs/screenshots/music.jpg) |
| ![Movie library](docs/screenshots/library.jpg) | ![Details](docs/screenshots/details.jpg) |
| ![Programme guide](docs/screenshots/guide.jpg) | ![Settings](docs/screenshots/settings.jpg) |

*Screenshots are from the built-in demo library.*

## Features

- **Media Center's start menu:** Search, Pictures + Videos, Music, Movies, TV Shows, Live TV and
  Tasks, with the tiles in Media Center's order and in its style (a mosaic for music and pictures,
  a shelf of cases for movies, a strip for TV). Rows only appear for what your server has.
- **Galleries** with pivots (title, genre, year, date added, unwatched, favorites), covers that
  lift on focus, series and album pages, people, collections and search.
- **Playback that avoids conversion:** the TV's own hardware decoders first, plus an FFmpeg
  decoder for DTS, TrueHD and the like; Dolby Digital, Dolby Digital Plus, DTS, TrueHD and Atmos
  passed through to a receiver; HDR and Dolby Vision where the TV supports them;
  high-resolution audio. When something can't play as-is, it steps down gently: different
  output settings first, then the server converting only the sound, then a full conversion.
- **Optimize for this TV:** checks the screen, decoders, sound output, connection and memory,
  and sets quality, resolution and audio to suit.
- **The remote, as in Media Center:** with the controls hidden, OK pauses, Left replays 7 s and
  Right skips 30 s (with preview thumbnails where the server has them). Each can be turned off.
- **Skip intro and credits** (Jellyfin media segments), next-episode countdown, sleep timer,
  subtitles (text and picture-based), audio and subtitle language preferences.
- **Live TV:** channel guide, now-playing banner, movie guide, recordings and scheduling.
- **Pictures:** slide shows with transitions, captions and music.
- **Servers:** finds Jellyfin servers on your network, keeps several servers and users, signs in
  with a password or Quick Connect.
- **Video behind the menus:** leave the player and the video keeps playing, dimmed, behind the
  menus; music plays on in a corner inset.
- **Server plugin (optional):** with the [Media Center plugin](plugin/README.md) on your Jellyfin
  server, an administrator can set and lock the app's settings (for everyone or per user), show
  notices, announce updates, and brand the app with their own chime, sounds, logo, intro title,
  backdrop and accent colour.

Needs Android 6.0 or later (Android TV and Google TV) and a Jellyfin server (skip intro and
credits need Jellyfin 10.10 or later). **Try the demo** on the sign-in screen browses a sample library without a server.

## Install

Download the APK from the [latest release](https://github.com/madmaxx636/MediaCenter-Jellyfin/releases/latest):

| File | For |
|---|---|
| `…-arm32.apk` | Almost every TV and TV box (onn, Chromecast with Google TV, Fire TV, most smart TVs) |
| `…-arm64.apk` | 64-bit devices such as the Nvidia Shield |
| `…-universal.apk` | Any device, if you're not sure (larger) |

Install it with a downloader app on the TV, or from a computer with network (wireless) debugging
turned on in the TV's developer options, using the address and port it shows:

```bash
adb connect <tv-ip>:<port>
adb install -r MediaCenter-for-Jellyfin-0.9.7-arm32.apk
```

## Build

Needs JDK 17 or later (Android Studio's bundled JBR works) and the Android SDK.

```bash
./gradlew assembleDebug          # a debug build
./gradlew assembleRelease        # release APKs, one per ABI plus a universal one
```

Release builds are signed with the key described in `signing/keystore.properties` (not in the
repository; `storeFile`, `storePassword`, `keyAlias`, `keyPassword`); without it they fall back
to the debug key.

### Sounds

The interface sounds are the app's own, rendered by `tools/make_sounds.py`
(`python3 tools/make_sounds.py app/src/main/res/raw`; needs numpy and ffmpeg). For your own
builds you can use other sounds by putting `custom_focus`, `custom_select`, `custom_back`,
`custom_error` or `custom_intro` (`.ogg`) in `app/src/localres/raw/`, which is ignored by git.
Builds for sharing should leave them out: `./gradlew assembleRelease -Ppublic`.

## Layout

| Path | What |
|---|---|
| `data/` | Jellyfin API client, repository, settings, saved servers and the offline demo |
| `playback/` | ExoPlayer set-up and fallbacks, device codec detection, auto-tuning, stereo downmix |
| `ui/theme/` | Palette, type, backdrop and ribbons |
| `ui/components/` | Focus, glass, controls, glyphs and tile art |
| `ui/screens/` | Start menu, galleries, details, player, guide, photos, settings, sign-in, intros |
| `tools/` | Sound and banner generators |
| `plugin/` | The Jellyfin server plugin (C#, .NET 10) |

## License and credits

Media Center for Jellyfin is free software under the [GNU General Public License, version 3](LICENSE).

- The Jellyfin logo is © Jellyfin contributors, licensed under
  [CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/); the orb artwork made from it
  is shared under the same license.
- Built with [AndroidX Media3](https://github.com/androidx/media), Jetpack Compose,
  [Ktor](https://ktor.io) and [Coil](https://coil-kt.github.io/coil/) (Apache 2.0), and
  [Jellyfin's FFmpeg decoder for Media3](https://github.com/jellyfin/jellyfin-androidx-media) (GPL 3.0).

This project isn't affiliated with or endorsed by Microsoft or the Jellyfin project. Windows and
Windows Media Center are trademarks of Microsoft. No Microsoft artwork, fonts or sounds are included.
