# Phase 0 v0.9.1 — compile fix

GitHub Actions run #10 exposed exactly two Kotlin compilation errors.

Fixed:
1. `UtteranceProgressListener.onAudioAvailable()` now uses the Android API's
   non-null `ByteArray` signature and checks `audio.isNotEmpty()`.
2. `BenchmarkViewModel.choosePocketDiagnostic()` is now explicitly present,
   matching the UI callback used by the Pocket short-test button.

No runtime/benchmark logic was otherwise changed from v0.9.
The 180-second Pocket stage diagnostics remain intact.
