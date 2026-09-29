# Phase 0 v0.10.1 — Kokoro CI publishing-plugin fix

GitHub Actions run #12 failed before Bolo/Kitten compilation.

Root cause:
The pinned upstream Kokoro project applies the Vanniktech Maven publishing
plugin even when Bolo only needs a local `:library:assembleRelease` AAR.
GitHub failed resolving:

    com.vanniktech.maven.publish:0.34.0

That plugin is unrelated to TTS inference and unnecessary for our local build.

Fix:
- `scripts/patch_kokoro_runtime.py` now strips the publishing plugin from:
  - upstream root `build.gradle.kts`
  - upstream `library/build.gradle.kts`
- removes the upstream `mavenPublishing { ... }` block
- CI verifies those publishing references are absent before invoking Gradle
- ONNX Runtime tuning, af_heart voice staging, Kitten adapter, ARM64-only Bolo
  packaging, and all Phase-0 diagnostics remain unchanged

Run #12 never reached app compilation, so there was no evidence of a Kitten
compile failure in that run.
