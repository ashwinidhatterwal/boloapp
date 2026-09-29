# KittenTTS Micro Phase-0 Test

This is a controlled model-quality experiment.

Bolo itself is unchanged. The only change is the separately installed Kitten
Android engine:

- Previous engine: KittenTTS Nano 0.8, 15M parameters
- New test engine: KittenTTS Micro 0.8, 40M parameters
- Official Micro ONNX size: 41,384,970 bytes
- Official Micro voices file: 3,278,902 bytes
- Sample rate: 24 kHz
- Voices: Bella, Jasper, Luna, Bruno, Rosie, Hugo, Kiki, Leo

## Why Micro first

Nano measured about 3.95x realtime on the Vivo test phone, leaving substantial
performance headroom. Micro is the next controlled step toward better voice
quality without jumping directly to the 80M Mini model.

## Install

The custom Micro APK uses the same Android package name as the Nano engine:

    com.stellonlabs.kittentts

Because the GitHub-built Micro APK is signed with a different debug key than the
public Nano release, Android will normally refuse to install it over the existing
Nano APK.

Therefore:

1. Keep Bolo installed.
2. Uninstall only the separate `KittenTTS` / `KittenTTS Nano` engine app.
3. From GitHub Actions, download artifact:
   `kitten-tts-micro-v0.8-arm64-espeak-fixed`
4. Extract the artifact ZIP.
5. Install:
   `KittenTTS-Micro-v0.8-arm64-espeak-fixed.apk`
6. Open Bolo.
7. Select Kitten again.

Bolo v0.10 may still display the label `KittenTTS Nano (local)` because that
label belongs to the frozen benchmark adapter. For this controlled run, the
installed external engine is Micro despite that stale label.

## Test

Use exactly the same settings and passage as the Nano benchmark:

1. Voice: Bella
2. Speed: 1.00x
3. Narration
4. Generate
5. Play and judge naturalness
6. 5x stress

Record:
- Generation time
- Audio duration
- RTF
- Generation speed
- Temperature
- Thermal status
- Subjective voice quality versus Nano
- Subjective voice quality versus Kokoro FP32

## Decision gate

Micro is promising if:
- voice quality is clearly better than Nano, and
- sustained RTF remains below 0.7

If Micro quality remains insufficient, the next controlled experiment will be
KittenTTS Mini 80M. We will not add Mini until Micro has been measured.
