# Phase 0 v0.2

Complete Phase-0 Offline Narrator benchmark package.

## CI fixes included

- `actions/setup-java@v5`
- `android-actions/setup-android@v4`
- Android API 36
- Android Build Tools 36.0.0
- Gradle 9.6
- `compileSdk = 36`
- `targetSdk = 36`

This addresses the first two GitHub Actions failures:
1. obsolete Android SDK `tools` package requested by setup-android v3;
2. unavailable `platforms;android-37` package.

The replaceable `TtsEngine` architecture and Phase-0 benchmark functionality remain intact.
