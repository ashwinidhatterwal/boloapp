# Offline Narrator Lab — Phase 0

Android benchmark for the **Offline Natural Audiobook Reader** project.

This is intentionally *not* the full reader yet. Its job is to answer the first hard
question on a real phone: **which local TTS engine is good and fast enough for hours of
book narration?**

## Included now

- replaceable `TtsEngine` architecture;
- Android System TTS baseline;
- Kokoro-82M local adapter;
- `.onnx` model import using Android's document picker;
- narration/dialogue/number/long-form test passages;
- generation time;
- generated audio duration;
- real-time factor (RTF);
- 5× repeat stress test;
- app PSS and native memory;
- battery temperature;
- Android thermal status;
- WAV playback;
- no network permission;
- GitHub Actions APK build.

## Important: model is not inside the APK

Kokoro model weights are large, so Phase 0 keeps them outside the APK.

In the app:

1. choose **Kokoro**;
2. tap **Model page**;
3. download a compatible Kokoro v1.0 ONNX file;
4. return to the app;
5. tap **Import .onnx** and select it.

For the first test, use the full v1.0 ONNX model if possible. We should test quantized
models only after the baseline path is confirmed on-device.

The benchmark adapter uses the `af_heart` voice bundled by the current Android wrapper.

## What RTF means

```text
RTF = generation time / audio duration
```

- `0.25` = about 4× realtime generation
- `0.50` = about 2× realtime
- `1.00` = generation speed equals listening speed
- `>1.00` = generation is slower than playback

For the eventual audiobook reader, a comfortably sub-1.0 RTF gives us room to generate
ahead of the listener.

## Build automatically on GitHub

The included workflow is:

```text
.github/workflows/android-debug.yml
```

On every push to `main`/`develop` it runs unit tests, builds the debug APK and uploads
it as a GitHub Actions artifact named:

```text
offline-narrator-phase0-debug
```

### Phone-only GitHub workflow

If working from Termux:

```bash
pkg update
pkg install git unzip
unzip offline-narrator-phase0.zip
cd offline-narrator-phase0

git init
git branch -M main
git add .
git commit -m "Phase 0 offline TTS benchmark"
git remote add origin YOUR_GITHUB_REPOSITORY_URL
git push -u origin main
```

Then open the GitHub repository on your phone:

**Actions → Build Android Debug APK → latest run → Artifacts → offline-narrator-phase0-debug**

Download the artifact ZIP, extract it, and install `app-debug.apk`.

## Current architecture decision

`KokoroTtsEngine` is an adapter, not the app architecture.

Later we can add:

```text
PocketTtsEngine
KittenTtsEngine
IndicTtsEngine
FutureTtsEngine
```

without rewriting the player or future Narration Director.

## Next gate

Do not begin the large-book reader until we have real measurements from at least one
local neural engine on the target phone.

After this APK works, next Phase-0 update is:

1. add the second engine adapter;
2. compare the same passages;
3. select a provisional production engine;
4. then build EPUB → chunk queue → cache → continuous playback.
