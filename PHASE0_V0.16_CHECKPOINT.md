# Phase 0 v0.16 — Supertonic clean trial

Purpose:
Compare the current natural Kokoro baseline against Supertonic 3 without
carrying forward the old benchmark UI and abandoned engine experiments.

Bolo main UI now contains only:
- Kokoro
- Supertonic 3
- narration text
- voice selector
- Generate / Play
- compact RTF result
- collapsed details

Removed from the active package:
- Pocket adapter
- Kitten adapter
- Android System benchmark option
- Kokoro Performance Lab
- manual CPU profile matrix
- FP16 import UI
- old Pocket/Kitten setup docs
- Kitten CI workflow

Kokoro:
- FP32 imported model
- fixed ALL_OPT / up-to-8-thread profile
- token-aware long-text chunking retained

Supertonic:
- separate companion APK: `Bolo-Supertonic-Engine-v0.1.apk`
- uses `audio.soniqo:speech:0.0.22`
- Supertonic 3 LiteRT
- 44.1 kHz
- voices F1-F5 / M1-M5
- model downloaded once by the companion app
- synthesis is local after setup

Why companion process:
- avoids native runtime conflicts with the existing Kokoro/ORT stack
- keeps the Bolo UI and app process clean
- allows Supertonic to be removed independently
- Bolo itself retains no Internet permission

Test:
1. Install both APKs from the GitHub artifact.
2. Open `Bolo Supertonic Engine`.
3. Tap `Download / verify model` and wait for `Ready`.
4. Open Bolo.
5. Use the standard narration sample.
6. Generate Kokoro, listen, record RTF.
7. Generate Supertonic with F3, listen, record RTF.
8. Compare naturalness first, then speed/thermal behavior.

Important:
The first Supertonic checkpoint intentionally uses the maintained Soniqo
LiteRT bundle instead of the smaller community quantization. If voice quality
and device performance are good, a later build can evaluate the smaller
WI8/AFP32 bundle without changing Bolo's TtsEngine abstraction.
