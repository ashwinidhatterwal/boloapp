# Phase 0 Architecture

The benchmark intentionally treats TTS as a replaceable renderer.

```text
Benchmark UI
   ↓
BenchmarkViewModel
   ↓
TtsEngine interface
   ├── SystemTtsEngine
   ├── KokoroTtsEngine
   ├── PocketTtsEngine (future adapter)
   ├── KittenTtsEngine (future adapter)
   └── FutureTtsEngine
   ↓
SynthesisResult
   ↓
Audio player + metrics
```

The production reader will place the Narration Director, normalization, pronunciation,
segmenter and cache *above* this interface. That means changing the model does not
change book parsing, narration logic or playback.

## Engine contract

Every engine returns the same fields:

- generated audio file;
- audio duration;
- generation time;
- optional sample rate.

The benchmark computes RTF (real-time factor):

```text
RTF = generation time / generated audio duration
```

RTF below 1.0 means synthesis is faster than listening time.

## Model storage

Kokoro weights are deliberately not inside the APK. Android's document picker copies
the selected ONNX model into private app storage. This keeps the APK small and lets us
swap model files without rebuilding the app.
