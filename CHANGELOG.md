# Changelog

## 1.0.0

Everything since 0.9.7, the first public release.

**New in the player**
- **Match frame rate:** the TV switches to the film's own rate (23.976, 24, 25, 50 Hz…) while it
  plays, so pans don't judder, and back afterwards.
- **Styled subtitles:** ASS/SSA subtitles (common with anime) with their own fonts, colours,
  positions and animations.
- **Chapters:** marks on the progress bar; skip next and previous go chapter by chapter.
- **Playback panel** (the gear in the controls): sound and subtitle sync, speed, night mode.
- **Night mode:** evens out loud explosions and quiet dialogue.
- **Quick info bar:** Up shows the time, when it ends, the picture and sound format, the bitrate,
  and whether it's playing as-is or converted.
- **Remembers your choices per show:** pick a soundtrack or subtitles once and the rest of the
  show's episodes follow.
- **Live TV mini guide:** OK brings up what's on now and next on each channel, without leaving
  the picture.
- **The app's own video decoder** (FFmpeg, with dav1d for AV1) where the TV has none: AV1 on older
  boxes, MPEG-2 and more. Choose it first in settings › codecs › video decoding if you like.

**New everywhere else**
- **Updates itself** from GitHub, after Android asks you to confirm.
- **Hold OK for options** on anything: play or resume, shuffle, add to queue, play next, more info,
  go to series or album, mark watched, favorites, record.
- **Screensaver:** your server's backdrops with each title's logo, a slow zoom and cross-fades,
  and the time. Music keeps playing and a paused video stays put; any button brings you back. It
  can also be the TV's own screensaver.
- **Music:** a visualizer that moves with the music, and lyrics from Jellyfin that follow along.
- **Voice search**, and "search Media Center for…" from the Assistant.
- **Library lists everywhere:** every Movies and TV Shows tile (continue watching, recently added,
  favorites, next up, collections) opens with the library's view, list, sort and search. New
  lists: watched, continue watching, next up, years, parental ratings, and video type (SD, HD,
  Full HD, 4K / UHD, 3D, DVD, Blu-ray, ISO); sort by bitrate and by last played.
- **Collections** have their own tile in Movies, with a shelf of covers.
- **Slide shows** pan and zoom a different way for each picture.
- **Media Center server plugin** (optional): the server can set and lock the app's settings (for
  everyone or per user), show notices, announce updates, and brand the app with its own chime,
  sounds, logo, intro title, backdrop and accent colour.

**Changed**
- The start menu has no Search row: every row has its own search tile, and search finds pictures
  and home videos too. The movie guide tile is gone.
- Changing season keeps you on the season bar instead of dropping into the episodes.
- Back in the player closes what's open in one press.
- Smoother start menu.

**Fixed**
- Trickplay previews (they need the server sign-in) and live TV that stopped after a few minutes
  (it now tunes in again by itself).
- Settings sections below the first few couldn't be reached at some interface sizes.

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
