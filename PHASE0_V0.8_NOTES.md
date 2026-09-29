# Phase 0 v0.8 — Pocket comparison

This version adds a second neural TTS candidate without coupling Pocket's native
runtime into Bolo.

## New engine

`PocketTtsEngine` targets the separately-installed Android package:

    org.pockettts.android.engine

Pocket is accessed through Android's TextToSpeechService API. The Pocket app
owns its ONNX/native model lifecycle; Bolo owns only the benchmark adapter.

This keeps the reader architecture model-agnostic:

    TtsEngine
      -> SystemTtsEngine
      -> KokoroTtsEngine
      -> PocketTtsEngine
      -> future engines

## Stable Pocket test target

Phase 0.8 is built around the public Pocket Android Engine v0.5.2 release and
its English FP32 model pack.

## Reliability

- 60-second timeout per synthesis pass.
- Explicit Cancel synthesis button.
- Pocket/System cancel calls Android TTS stop().
- Existing 5x stress and long (~5 minute) passage remain available.
- v0.7 Kokoro ORT/runtime diagnostics remain intact.

## Why Pocket stays separate for now

The Pocket Android engine already has a native PocketTTS.cpp/ONNX integration,
model-pack importer, voice handling and cancellation. Duplicating all of that
inside Bolo before we know Pocket wins would increase code and licensing burden
for no benchmark benefit.

If Pocket clearly wins Phase 0, we can later decide whether to:
1. keep it as an Android engine dependency, or
2. internalize the native runtime behind Bolo's Pocket adapter.
