# ClaudeCast: handoff notes

Context for picking up this project in a new session (e.g. Claude Code). Written
2026-11-18 at the end of a long claude.ai session. Everything below reflects what
was decided and built there.

## Who the user is and how he works

- Goes by **"Sir"**. Hutchinson, Kansas. Phone: **Google Pixel 10 Pro XL**. His own TV: **Samsung** (Tizen).
- Not an Android developer; he tests on his phone and reports back, often tersely.
  Prefers short, direct answers that lead with the result.
- He installs builds by tapping the APK link from GitHub Releases on his phone.
- **Strong privacy stance.** Doesn't want big companies profiting from his data.
  Avoid Google services/SDKs, accounts, analytics, trackers. Prefer open, keyless sources.
- **Will only pay for physical media** (CD, cassette, vinyl). Don't add "buy" links.
- Goal for later: put ClaudeCast on his parents' TVs (LG, Hisense, Roku) as a **single
  app that does everything with just the remote**, no switching inputs or apps.

## Repo, branch, builds

- Repo: `github.com/cac67502/claudecast` (private). Package `com.aide.claudecast`.
- **All new work is on branch `claudecast-1.2`.** `main` is still the old v1.0 (podcasts only).
  Plan: merge `claudecast-1.2` into `main` once he's happy with testing.
- Commit identity used: `cac67502 <cac67502@users.noreply.github.com>`.
- CI: `.github/workflows/build.yml` runs `gradle testDebugUnitTest assembleDebug`, then
  publishes the APK as a GitHub Release `build-N` (asset `ClaudeCast.apk`, marked latest).
  He downloads from `https://github.com/cac67502/claudecast/releases/download/build-N/ClaudeCast.apk`.
  Latest at handoff: **build-22**.
- CI fix already made: `android-actions/setup-android@v3` broke (removed `tools` package);
  workflow now uses the runner's preinstalled SDK via `$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager`.
- **Fixed signing key** committed at `app/claudecast.jks` (pass/alias `claudecast`) so every
  build installs over the previous one. He already did the one-time uninstall. **Never
  change the key** or he loses subscriptions/playlists again.
- Version: `versionCode 4`, `versionName 1.3`.
- Stack: Kotlin, Jetpack Compose M3, Media3/ExoPlayer (+HLS) with MediaSessionService,
  OkHttp, kotlinx.serialization, Coil, Navigation Compose. Manual DI via `ServiceLocator`.
  compileSdk 35, minSdk 26, Java 17. No Room. Cleartext HTTP is allowed in the manifest.

## What's in the app (bottom tabs: Library, Music, TV, Search, Settings)

### Podcasts (original v1.0)
iTunes Search API for discovery + direct RSS parsing (`FeedParser`), Podcasting 2.0 tags:
transcripts, chapters, soundbites, funding, persons, season/episode, value/V4V. Sleep timer,
tracking-prefix URL stripper.

### Volume Guard (Settings screen) — WORKING, confirmed
Pixel's "Volume lowered to safer level" auto-drop (hearing protection) has no off switch.
`volumeguard/VolumeGuardService` is a NotificationListenerService: sees the System UI alert,
taps its Undo action, then restores media volume to the level before the drop; logs to an
on-screen activity log with a "drops caught" counter. Sideloaded apps need
App info ▸ ⋮ ▸ **Allow restricted settings** before notification access can be granted
(he's done this). Confirmed catching drops at work.

### Music tab
- **Radio**: Radio Browser API (`RadioApi`), no key, mirror failover. **English only**,
  sorted by **clickcount** (24h plays, shown on each row). Genre chips, search, favorites.
  **Local** chip: user picks a US state once (no GPS/location permission); searches the
  directory by full state name and abbreviation. Default state is whatever he picked (KS).
- **Record this song** (`StreamRecorder`): tape-deck style, one song per press, for personal
  use. Listens to the station's ICY stream in parallel, keeps the current song in memory,
  saves it on the next StreamTitle change to `Music/ClaudeCast/<station>/` (MP3 w/ ID3 or AAC).
  Deliberately **no auto-record-everything, no export/share**. Toggle "Song recording" to
  save data. HLS stations and stations without ICY metadata can't be recorded.
  (Legal framing agreed with him: home taping is protected under the Audio Home Recording
  Act; software stream ripping is a gray area; keep it one-song-at-a-time, personal.)
- **Heard on radio** list: heart button saves artist/title/station/date. **No buy links.**
- **Library**: Songs / Artists / Albums / Playlists from MediaStore + recordings, shuffle/repeat.
- Data in `music.json` via `MusicStore` (favorites, heard, playlists, recordings meta,
  localState, tvFavorites, captureEnabled).

### TV tab
- **Live TV** (`TvApi`): iptv-org public playlists (`languages/eng.m3u`, `subdivisions/us-xx.m3u`),
  cached 12h. **Filtered to legitimate free sources only**: allowlist of FAST services and
  official broadcaster hosts (Pluto via jmp2.uk, Tubi, Samsung, Amagi, Wurl, CBS News, PBS,
  public-access hosts, etc.); drops bare-IP servers, entries needing spoofed headers, and
  geo-blocked feeds; paid cable brand names (ESPN, CNN, Discovery…) only allowed from the
  free services that license them. **Do not add pirate/restream sources.** Category chips,
  search, favorites, Local (by state). Kansas only has ~2 local channels in that list
  (Ace TV KCKS-LD, WPS-TV); Wichita affiliates (KSN/KAKE/KWCH) aren't freely available.
- **Movies** (`MovieApi`): Internet Archive `feature_films` (public domain, mostly pre-1970),
  most-downloaded first, genre chips, search, picks best mp4, resume points in SharedPreferences.
  Pluto/Tubi on-demand catalogs are not accessible legally; told him Hoopla/Kanopy for newer films.
- Video player (`TvPlayerScreen`/`TvVideo` with Media3 PlayerView), full screen, picture-in-picture
  on Home (`MainActivity.onUserLeaveHint`, `PipState`).

### Playback behavior he confirmed he wants
- Screen off: keeps playing. Backing out of the app: keeps playing.
- **Swiping the app away from Recents: stops everything and closes** (`PlaybackService.onTaskRemoved`).

### Cast to TV (DLNA) — IN PROGRESS
He chose **DLNA over Google Cast** (privacy; Samsung doesn't do Google Cast).
- `DlnaCaster`: SSDP discovery, AVTransport SOAP (SetAVTransportURI/Play/Pause/Seek/
  GetPositionInfo), retries without metadata / as video/mpeg, retries Play, step-by-step
  details log shown under "Show details" in the cast dialog (`CastUi`).
- Cast button on every player screen. Stop casting resumes on the phone where the TV was.
- Movies and podcasts cast directly: **movie casting works** on his Samsung.
- Songs stored on the phone can't be cast (TV can't reach content:// URIs). Could add a
  local HTTP server later if he wants.
- **HLS live channels go through `HlsRelay`**: the phone follows the playlist and serves one
  continuous MPEG-TS stream on the LAN (no re-encode; AES-128 supported; resolves URLs
  after redirects — that fixed Pluto; browser User-Agent). `CastKeepAliveService` is a
  foreground service ("Casting to …" notification with Stop) so Android doesn't freeze the
  relay (fixed a ~6 min drop).
- **Ad breaks**: Pluto switches timelines (HLS discontinuities) for each ad. Samsung dropped
  the connection, ran its Smart Hub connection test, then exited to the home screen.
  Restarting the TV at each break (builds 20–21) didn't satisfy Samsung.
  **Build 22** replaced that with `TsStitcher`: rewrites PTS/DTS/PCR after each
  discontinuity so the timeline continues, renumbers continuity counters, clears
  discontinuity flags. Verified with ffmpeg on synthetic show→ad→show chunks (smooth
  timestamps, zero decode warnings); `TsStitcherTest` checks Kotlin output byte-for-byte
  against that reference (fixtures in `app/src/test/resources/ts/`).
  **Build 22 not yet tested by him on the Samsung.** Possible remaining issue: resolution
  change between show and ad could still make some TVs glitch. Reconnect-on-drop fallback
  (`onRestartNeeded` → `DlnaCaster.restartTv`, max 15/min) is still in place.
  If it still fails, ask for a screenshot of Show details and the channel name.

## Open to-do list

1. Get his results on build 22 casting through ad breaks; fix as needed.
2. Merge `claudecast-1.2` into `main` when he's satisfied.
3. **Podcasting 2.0 update** (listener-facing tags not yet supported): `podcast:liveItem`,
   `podcast:podroll`/`remoteItem`, `podcast:trailer`, `podcast:alternateEnclosure`,
   `podcast:location`, `podcast:chat`, `podcast:socialInteract`, `podcast:publisher`,
   `podcast:updateFrequency`, `podcast:image` (phase 8, in progress). Skip host-only tags
   (txt verification, podping, block, etc.).
4. **TV mode** (after he buys hardware): Google TV sticks for each TV (his Samsung and his
   parents' LG/Hisense/Roku can't run APKs; their built-in tuners aren't reachable from
   apps). Leanback launcher entry + banner, D-pad focus navigation, big tiles, and a
   **combined channel guide** mixing locals + free channels, channel up/down.
5. **HDHomeRun support** (after he buys one, recommended **Flex Duo**, ATSC 1.0, because
   ATSC 3.0 is DRM-protected on many stations): discover on LAN, read lineup.json, add locals
   to the guide on phone and TV mode.

## Things that were ruled out (and why)

- YouTube Music / NewPipe-style clone: requires circumventing YouTube protections; also
  still sends data to Google.
- Recording radio into a library automatically / mass ripping; buy links.
- Pirated IPTV channels.
- Google Cast SDK (privacy; Samsung unsupported).
- Using the phone's or TVs' built-in TV tuner (Pixel has none; TV tuners are locked to the maker's app).

## Environment notes for whoever builds next

- The claude.ai cloud workspace could not reach radio-browser, iptv-org.github.io, archive.org,
  Maven Central or GitHub artifact storage; everything was verified via GitHub Actions builds.
  iptv-org playlists were inspected by cloning `iptv-org/iptv` branch `gh-pages`.
- `gh` CLI works against the repo for runs/releases.
