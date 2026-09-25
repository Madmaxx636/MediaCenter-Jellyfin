# Changelog

## 0.9.9

**New**
- **Updates itself:** finds new versions on GitHub, offers them on the start menu, and installs
  them after Android asks you to confirm. Also in settings › about; can be turned off in
  settings › general.
- **Hold OK for options** on anything: play or resume, shuffle, add to queue, play next, more info,
  go to series or album, mark watched, favorites, record. Library tiles offer open and shuffle.
- **Every Movies and TV Shows tile** (continue watching, recently added, favorites, next up,
  collections) opens with the library's view, list, sort and search.
- **More ways to list:** watched, not watched, favorites, continue watching, next up, years,
  parental ratings and video type (SD, HD, Full HD, 4K / UHD, 3D, DVD, Blu-ray, ISO); sort by
  bitrate and by last played.
- **Media Center server plugin:** the server can set and lock the app's settings (for everyone or
  per user), show notices, announce updates, and brand the app with its own chime, sounds, logo,
  intro title, backdrop and accent colour.

**Changed**
- The start menu no longer has a Search row: each row has its own search tile (Pictures + Videos
  now too, and search finds pictures and home videos).
- Collections have their own tile in Movies (moved from Extras), with a shelf of covers; the movie
  guide tile is gone.
- Smoother start menu: the rows and strips move without rebuilding the menu on every frame.

**Fixed**
- Trickplay previews (they need the server sign-in) and live TV that stopped after a few minutes
  (the app now re-tunes by itself).
- Settings sections below the first few couldn't be reached at some interface sizes.
- Trickplay previews could attach to the wrong video after switching quickly; the album page's
  "view queue" didn't appear or go when playback started or stopped.

## 0.9.7 — first public release

Media Center for Jellyfin: an Android TV client for Jellyfin in the style of Windows 7 Media Center.

**Look and feel**
- Startup animation after Media Center's own: a cut from black to the blue, the logo zooming back
  into focus, a glint and a lens flare, then the start menu settling in. Four other styles in
  settings (ribbons, light swirl, glass, aurora).
- Start menu with Media Center's rows and tiles in its order, in its style of tile art.
- The app's own interface sounds and startup chime.
- Animated ribbons behind the start menu, Aero glass, the clock at the top centre.

**Playback**
- Plays almost everything as-is: hardware decoders first, FFmpeg for DTS, TrueHD and similar,
  passthrough of Dolby Digital, Dolby Digital Plus, DTS, TrueHD and Atmos to a receiver, HDR and
  Dolby Vision where the TV supports them, high-resolution audio (FLAC and other lossless formats).
- Gentle fallbacks when something fails: plain output, then compatible stereo, then the server
  converting only the sound, then a full conversion.
- Optimize for this TV: detects the screen, decoders, sound output, connection and memory, and
  sets playback to suit.
- Player controls: OK to pause, Left/Right to replay and skip without the controls, info when
  paused, optional subtitles on replay and Down for subtitles.
- Skip intro and credits, next-episode countdown, trickplay previews, sleep timer.

**Everything else**
- Galleries with pivots, series and album pages, people, collections, search.
- Live TV guide, recordings and scheduling; picture slide shows; music with a queue.
- Finds servers on your network; several servers and users; Quick Connect.
- Tuned to stay smooth on 2 GB TV boxes; runs on Android 6.0 and later.
