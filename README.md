# ClaudeCast

A private, all-in-one Android media app, themed in Claude's clay-and-cream palette:
**podcasts, internet radio, music library, free live TV and public-domain movies**, with
casting to a DLNA TV. Built for a Pixel 10 Pro XL and a Samsung TV.

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
- Search and subscribe to any show in the iTunes directory.
- Background playback (Media3/ExoPlayer) with lock-screen controls, resume positions
  and variable speed.
- Podcasting 2.0 from the feed: chapters, transcripts (SRT/VTT/JSON/HTML), persons,
  Value 4 Value (display only), soundbites, season/episode.
- **Live shows:** if a podcast has a `podcast:liveItem`, the show page displays a
  "LIVE NOW" banner with a *Listen live* button, or the scheduled start time.

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
