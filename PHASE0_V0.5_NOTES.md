# Phase 0 v0.5 — full build-audit pass

This package addresses GitHub Actions run #5 and also hardens the build so one
failure does not unnecessarily hide other independent problems.

## Confirmed from run #5

The following already succeeded:
- Android SDK setup
- API 36 installation
- NDK/CMake installation
- Kokoro Android AAR build from source
- local AAR integration
- Gradle setup

The only reported AAR metadata failures were:
- lifecycle-runtime-compose 2.11.0 requires compileSdk 37+
- lifecycle-viewmodel-compose 2.11.0 requires compileSdk 37+

## Fixes in v0.5

1. Entire Lifecycle family pinned to 2.10.0:
   - lifecycle-runtime-ktx
   - lifecycle-runtime-compose
   - lifecycle-viewmodel-ktx
   - lifecycle-viewmodel-compose

2. `lifecycle-runtime-compose` is now explicit because the UI directly uses
   `collectAsStateWithLifecycle()`.

3. Kokoro source is pinned to commit:
   `593e2353954498b667e3bf3489a61723e6ba6b59`
   This is the upstream revision that has already built successfully in our CI.

4. CI now requests:
   - AAR metadata check
   - Kotlin compilation
   - unit tests
   - APK assembly
   with Gradle `--continue`, so independent failures are reported in the same run.

5. Android lint is run separately with `continue-on-error: true`, so lint findings
   do not prevent a valid debug APK from being uploaded.

## Static source audit

All Phase-0 Kotlin source, Android manifest, resources and Gradle files were reviewed.
No additional obvious compile-time source errors were found in:
- BenchmarkViewModel
- KokoroModelStore
- KokoroTtsEngine
- SystemTtsEngine
- TtsEngine contract
- BenchmarkScreen
- DeviceDiagnostics
- AudioPlayer
- benchmark unit test

The replaceable TTS engine architecture is unchanged.
