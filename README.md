# ClaudeCast

A native Android podcast player built around **Podcasting 2.0**, themed in Claude's
clay-and-cream palette. Personal replacement for Podcast Guru on a Pixel 10 Pro XL.

## No account, no API key

- **Discovery:** Apple's public **iTunes Search API** — no key, no signup, no auth.
- **Episodes + all PC2.0 metadata:** parsed **straight from each show's RSS feed**
  (the `podcast:` namespace), which is the canonical Podcasting 2.0 source anyway.

(This replaces the original Podcast Index backend, which blocks signups from free
email providers.)

## What it does

- **Search & subscribe** to any show in the iTunes directory.
- **Background playback** via Media3/ExoPlayer with lock-screen & notification controls,
  audio-focus handling, resume positions, and variable speed.
- **Podcasting 2.0 features**, parsed from the feed:
  - **Chapters** — from `<podcast:chapters>` JSON; tap to seek; current chapter highlighted.
  - **Transcripts** — `<podcast:transcript>` SRT / VTT / JSON / HTML parsed into cues;
    tap a line to seek; current line highlighted when timecodes exist.
  - **Persons** — `<podcast:person>` hosts/guests with images and roles.
  - **Value 4 Value** — `<podcast:value>` recipients and splits displayed (see note).
  - Soundbites, season/episode, funding links.

## Setup

1. **Open in Android Studio** (Hedgehog or newer). `File ▸ Open ▸` the `ClaudeCast`
   folder, let Gradle sync. The Gradle wrapper JAR isn't bundled — Android Studio
   regenerates it on first sync (or run `gradle wrapper` if you have Gradle on PATH).
2. **Build & run** to the Pixel (USB debugging on), or `Build ▸ Build APK(s)`.
3. That's it — open the app, search, subscribe, play. Nothing to configure.

## Stack

- Kotlin, Jetpack Compose (Material 3), Navigation Compose
- Media3 1.4.1 (ExoPlayer + MediaSessionService)
- OkHttp + `XmlPullParser` (RSS / PC2.0 feed parsing) + kotlinx.serialization (iTunes JSON)
- DataStore (speed/theme) + file-backed JSON (subscriptions / resume positions) — no Room/KSP
- Coil for artwork
- `compileSdk/targetSdk 35`, `minSdk 26`, Java 17

## Notes / honest caveats

- **Value 4 Value is display-only in v1.** ClaudeCast shows recipients and splits, but
  actually sending sats (boosts/streaming) needs a connected Lightning wallet
  (LNURL / NWC / Alby). That's the obvious v2 feature.
- Discovery is limited to shows listed in Apple's directory; once you have a feed URL,
  parsing works for any standard RSS feed. A "paste a feed URL directly" box would be a
  trivial add if you ever want shows outside the directory.
- iTunes Search is rate-limited (~20 req/min) — fine for personal use.
- Written carefully but **not compiled in the authoring environment** (no Android SDK
  there), so the first Gradle sync may surface a minor import/version nit. Everything is
  structured to compile clean — send me any error text and I'll fix it fast.

## Package

`com.aide.claudecast`
