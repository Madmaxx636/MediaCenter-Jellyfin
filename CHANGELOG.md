# Changelog

## Server plugin 1.0.2

- **Sound uploads work from any browser:** the plugin's page turned away .ogg sounds uploaded from
  Firefox, which labels them "video/ogg", so the sounds never reached the TVs. The plugin now works
  out what a file is from the file itself, whatever the browser calls it. FLAC sounds are accepted
  too, and the page's error messages no longer show stray quotes.

## 1.2.0

Fixes from a review of the whole app, tested on Android 6, 14 and 16 against Jellyfin 12.4, and
the libraries brought up to date.

**Fixed**
- **Saved sign-ins kept when the server is away:** choosing someone on "who's watching" while the
  server was off, asleep or not yet on the network deleted their saved sign-in and asked for their
  password. Now it says the server didn't answer and keeps them signed in. The sign-in is only
  dropped when the server itself turns it down.
- **Servers can't be deleted by accident:** holding OK on a saved server on "choose a server"
  removed it, and everyone signed in on it, at once. It now opens a menu (open, or remove from
  this TV), as holding OK does everywhere else.
- **Play next episode carries on into the next season:** an episode's queue stopped at the end of
  its season, so Up Next never offered the next season's first episode.
- **A plugin removed from the server is let go:** when the Media Center plugin was taken off a
  server, the TV kept its locked settings, colours and branding for good. It now notices the
  plugin is gone and unlocks them.
- **Video the TV can't decode plays as-is:** files only the app's own decoder can play (AV1 on
  older boxes, MPEG-2) were always converted by the server, even with video decoding set to the
  app's own decoder. The server is now told what that decoder plays, sized to what the box's
  processor keeps up with.
- **DVD and DVB subtitles:** a film with a DVD (VobSub) or DVB subtitle switched on was converted
  by the server, picture and all, just to burn the subtitle in. The app now draws them itself.
- **Picture subtitles in the right place:** Blu-ray, DVD and DVB subtitles were placed against the
  whole screen, so on a 4:3 or letterboxed film they came out stretched and in the wrong place.
  They now sit on the picture, at their own size.
- **Back on the screensaver** (Android 16): it could act on the screen underneath (or leave the
  app) instead of only waking the screensaver.
- **The welcome screen's buttons** ("next", "try the demo") were pushed off the bottom of the
  screen when a server was found on the network. The panel now scrolls to them.
- **Frame-rate switching** could start playback again behind the TV's home screen when Home was
  pressed while the TV was changing mode, or after you'd paused.
- **Skip buttons from the previous video:** after moving to the next video, a slow server could
  still deliver the previous video's intro and credits marks.
- **Clearer messages when a server can't be reached:** no more raw network errors, a server still
  starting up says so, Quick Connect being turned off on the server says so, and "doesn't trust
  the server's certificate" only shows for certificate problems.
- **The server's dashboard** showed every TV as Media Center 0.1.0; it now shows the app's real
  version.
- **Updates:** the app now offers the highest version on GitHub (not the most recently published
  one), and won't install a download its release has no checksum for.
- **Watched and favourites** changed on a details page now show in the lists you came from
  straight away; a search that couldn't reach the server no longer says "No matches."; a list
  that fails to refresh keeps showing what it had; trickplay previews that failed to load are
  tried again; and the details page stays on the section you chose as cast and similar titles
  arrive.
- **Wallpaper colours** now reach the last few places that stayed blue: genre tiles, the
  visualizer, the play button's glow, progress bars, the shade over video behind the menus and
  ticks in lists.

**Changed**
- Choices in settings are in order: next episode countdown, skip jumps ahead and subtitle size.
- Saving the queue as a playlist works for videos as well as songs, and the queue counts episodes
  and titles rather than calling everything songs.
- Updated Kotlin, the Android build tools, Ktor, Coil, kotlinx.serialization and AndroidX
  libraries. Compose stays on 1.12, the last version that runs on Android 6.

## 1.1.0

**Fixed**
- **Hold OK menus:** holding OK opened the menu and then chose its first option at once. The press
  that opens the menu no longer counts: move with up and down, and press OK again to choose.
- **Player menus:** after closing the subtitle, audio or playback menu, nothing in the controls
  was selected until they were closed and opened again. The cursor now goes back to the button
  that opened the menu.

**New in the player**
- **Up Next in the last seconds:** the next episode's card now comes up in an episode's last 30
  seconds and counts down to its end (settings › playback › up next appears: 15 seconds to 2
  minutes, or at the end as before). Back puts it away for that episode.
- **Find subtitles online:** "find subtitles online…" in the subtitle menu searches through your
  server for the video, in the language you pick. Choose one and it's downloaded, added to the
  video and switched on, carrying on from the same moment. Needs a subtitle plugin on the server
  (such as OpenSubtitles) and, for non-administrators, "Allow subtitle management".
- **Back hides the skip button:** Back puts away the skip intro (or credits, advert, recap)
  button; that part plays on, and the button doesn't come back for it.
- **Skip options renamed:** intro, credits, adverts, and recaps + previews.

**New everywhere else**
- **Wallpapers:** Media Center glow, aurora, horizon, bokeh or plain, in Media Center blue,
  midnight, teal, emerald, violet, crimson, sunset amber or graphite (settings › general).
  Menus, panels, tiles and the focus highlight follow the colour.
- **More sizes** (settings › general): font size 85–140%, interface size 80–130% and artwork size
  85–125%, all in 5% steps; player controls small, medium, large or extra large. Above 100%, the
  rows of covers don't all fit on screen, so they slide up and down to keep the one you're on in
  view.
- **Close** on the start menu asks: minimize (back to the TV's home, with the app kept as it is)
  or shut down (playback stopped and the app closed completely).
- **Quicker switching:** switching to another user or server keeps what the last person had
  loaded in a compressed file; switching back to them opens their start menu and lists at once,
  then catches up with the server. Only switching does this; a fresh start loads everything new.

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
