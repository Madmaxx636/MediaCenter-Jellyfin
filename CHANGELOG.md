# Changelog

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
