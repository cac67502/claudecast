# ClaudeCast

Android app (Kotlin, Jetpack Compose, Media3). Package `com.aide.claudecast`.

- Never change `app/claudecast.jks`: it is the fixed signing key, so every build installs over the last one. A different key forces an uninstall and loses the user's data.
- Builds come from GitHub Actions on every push; each one publishes a release `build-N` with `ClaudeCast.apk`.
- Privacy comes first: no Google services or SDKs, accounts, analytics or trackers. Prefer open, keyless sources.
- No buy links, no pirated streams, no mass recording of radio.
