# Phase 0 v0.11.1 — Kitten Micro eSpeak build fix

GitHub's Kitten Micro workflow reached the native Android build and failed in
eSpeak-NG 1.52.0 before Kitten Micro inference code was compiled.

Root cause:
- old eSpeak-NG 1.52.0 fallback in `speech.h`:
  `#define PATH_ESPEAK_DATA ("%cespeak-ng-data", PATHSEP)`
- under the Kitten Android CMake path + Android NDK 27 this becomes an integer
  expression at `strcpy(path_home, PATH_ESPEAK_DATA)`, producing:
  `no matching function for call to strcpy`

Fix:
- keep Bolo unchanged
- keep the official Kitten Micro ONNX and voices hashes unchanged
- replace eSpeak 1.52.0 with exact pinned commit:
  `ba90c8e9f440ad544f674a790bb5f53878b6ffc5`
- CI verifies the corrected POSIX fallback:
  `#define PATH_ESPEAK_DATA "/espeak-ng-data"`
- artifact renamed:
  `kitten-tts-micro-v0.8-arm64-espeak-fixed`

This iteration changes only the eSpeak source dependency used by the separate
Kitten Micro engine build.
