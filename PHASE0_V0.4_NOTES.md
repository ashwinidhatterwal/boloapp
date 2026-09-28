# Phase 0 v0.4

Complete Bolo / Offline Narrator Phase-0 benchmark package.

## Fix in this version

GitHub Actions run #4 successfully:
- installed Android API 36;
- installed NDK/CMake;
- built the Kokoro Android AAR from source;
- linked the local AAR into the app.

The build then failed during AAR metadata checking because the latest Compose
generation (1.12.x, selected by BOM 2026.09.00) requires compileSdk 37+.

This project intentionally stays on compileSdk 36 for the Phase-0 benchmark because
that SDK is reliably available in the GitHub runner.

The Compose BOM is therefore pinned to:

    androidx.compose:compose-bom:2026.04.01

That is the official April 2026 Compose 1.11 generation and is sufficient for every
UI feature used by this benchmark.

No TTS architecture changes were made:
- TtsEngine remains replaceable.
- Kokoro is still built from upstream source in CI.
- System TTS remains the baseline engine.
