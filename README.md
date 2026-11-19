# ClaudeCast

A private, all-in-one Android media app, themed in Claude's clay-and-cream palette:
**podcasts, internet radio, music library, free live TV and public-domain movies**, with
casting to a DLNA TV, and a TV mode for Fire TV sticks. Built for a Pixel 10 Pro XL,
a Samsung TV and a Fire TV Stick.

## Privacy first, no accounts

No Google services or SDKs, no accounts, no analytics, no trackers, no API keys. Every
source is open and keyless:

- **Podcast discovery:** Apple's public iTunes Search API.
- **Episodes and metadata:** parsed straight from each show's RSS feed.
- **Radio:** Radio Browser (~50k free stations).
- **Live TV:** community iptv-org lists, filtered to official free services only.
- **Movies:** free feature films from the Internet Archive.

## What it does

### Podcasts
- Search and subscribe to any show in the iTunes directory. The Podcasts tab has a search
  box at the top; results replace your subscriptions until you clear it.
- Background playback (Media3/ExoPlayer) with lock-screen controls, resume positions
  and variable speed.
- Podcasting 2.0 from the feed: chapters, transcripts (SRT/VTT/JSON/HTML), persons,
  Value 4 Value (display only), soundbites, season/episode.
- **Live shows:** if a podcast has a `podcast:liveItem`, the show page displays a
  "LIVE NOW" banner with a *Listen live* button, or the scheduled start time.
- **Video podcasts:** episodes whose feed offers video (a video enclosure or a
  `podcast:alternateEnclosure` video source) get a Video tag and a camera button that
  plays them in the full-screen video player, on phone and TV. If the device can't
  decode the video, the player says so instead of showing a black screen.

### Radio and music
- Search free stations by name or genre, with favorites and now-playing song info.
- Heart a song to save it to a **Heard** list.
- **Record this song:** one press saves the current song to `Music/ClaudeCast/<station>/`
  (personal listening, one song per press).
- Music library of everything on the phone plus recordings, browsed by Songs, Artists,
  Albums and Playlists, with shuffle and repeat.

### Live TV and movies
- Free live TV channels from official free-TV services.
- Public-domain feature films from the Internet Archive.

### Fire TV and Android TV
- Runs on Fire TV sticks and Android TV devices, with a TV launcher entry, a visible
  focus ring for remote-control navigation, and channels and video that open full screen
  (Back leaves the player). Search boxes open the on-screen keyboard only when OK is
  pressed, so Up and Down can move past them, and the podcast grid adapts to the wide screen.
- Live channels that fall too far behind rejoin the live edge on their own, and the screen
  stays awake while video plays.
- Optionally opens by itself when the device boots. Cast buttons and picture-in-picture
  are hidden on TV.
- Installed by sideloading over adb (`adb connect <ip>:5555`, then `adb install`).
- Limits: the app is still touch-first in places, and a device can only play what its
  hardware decodes (a 3rd-gen Fire TV Stick tops out at 1080p H.264). Fire OS keeps
  Amazon's launcher as Home, so ClaudeCast can't replace it.

### Casting to a TV (DLNA)
- Cast video to a DLNA renderer such as a Samsung Tizen TV. No Google Cast SDK.
- `HlsRelay` follows the HLS stream and serves one continuous MPEG-TS stream to the TV.
- `TsNormalizer` gives every piece one fixed stream layout and fills audio-less ad
  pieces with silent audio. `TsStitcher` keeps timestamps continuous across ad breaks.
  Together they stop the TV dropping the cast at ad breaks.
- Cast events go to logcat under the tag `ClaudeCastCast`.

### Volume Guard
Settings ▸ Volume Guard taps Undo on Android's "Volume lowered to safer level" alert
and restores the previous media volume. Needs notification access (on sideloaded
installs: App info ▸ ⋮ ▸ Allow restricted settings first).

## Install

Every push to GitHub runs Actions, which builds the app and publishes a release
`build-N` containing `ClaudeCast.apk`. Download the newest APK and tap it to install.
Builds are signed with a fixed key, so each one installs over the last.

To build yourself, open the folder in Android Studio (Hedgehog or newer) and let Gradle
sync, or run `gradle testDebugUnitTest assembleDebug`.

## Stack

- Kotlin, Jetpack Compose (Material 3), Navigation Compose
- Media3 (ExoPlayer + MediaSessionService)
- OkHttp, `XmlPullParser` (feeds), kotlinx.serialization
- DataStore and file-backed JSON (no Room/KSP)
- Coil for artwork
- `compileSdk/targetSdk 35`, `minSdk 26`, Java 17

## Notes

- Value 4 Value is display-only; sending sats would need a Lightning wallet.
- Podcast discovery is limited to Apple's directory; any RSS feed works once you have
  its URL.

## Package

`com.aide.claudecast`
