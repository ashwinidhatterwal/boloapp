# Phase 0 v0.10 — KittenTTS + ARM64 APK

## Why KittenTTS

Pocket FP32 reached native-start quickly but produced no first audio after 180 s
on the target Vivo. Phase 0.10 therefore adds a genuinely small Android-oriented
candidate instead of continuing Pocket debugging.

Kitten test target:
- Android engine: gyanendra-baghel/kittentts-android v1.0.0
- package: com.stellonlabs.kittentts
- model family: KittenTTS Nano 0.8
- model size: ~25 MB
- parameters: ~15M
- sample rate: 24 kHz
- voices: Bella, Jasper, Luna, Bruno, Rosie, Hugo, Kiki, Leo
- engine APK includes its model; no separate model ZIP is required

## Bolo architecture

The model abstraction remains unchanged:

TtsEngine
  -> SystemTtsEngine
  -> KokoroTtsEngine
  -> PocketTtsEngine
  -> KittenTtsEngine

Kitten runs in a separate Android TextToSpeechService for Phase 0, keeping its
native runtime isolated until it proves itself.

## APK slimming

Bolo is now ARM64-only:
- `arm64-v8a`
- removes unused armeabi-v7a / x86 / x86_64 ONNX native libraries from Bolo
- intended for the current modern Android test device profile

This does NOT bundle the large Kokoro model. Kokoro remains user-imported.

## Test order

1. Install KittenTTS Android v1.0.0.
2. Install Bolo v0.10.
3. Select Kitten.
4. Narration -> Generate -> Play.
5. Record perceived quality.
6. Run 5x stress.
7. Compare RTF and first-audio behavior with Kokoro FP32.

Primary success target:
- natural enough for long-form listening
- RTF <= 0.7 preferred
- first audio in a few seconds
- stable temperature and memory
