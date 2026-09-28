# Phase 0 v0.6 — voicepack + system-language fix

## Kokoro initialization bug fixed

The v0.5 APK could import and load the Kokoro ONNX model, but initialization failed with:

    Voice 'af_heart' is not available. Bundle it or call addVoice() first.

Cause:
The upstream Kokoro Android repository does not commit `af_heart.bin` into source
control. The voice must be staged before building the AAR.

Fix:
GitHub Actions now downloads the official `af_heart.bin` voicepack from
`onnx-community/Kokoro-82M-v1.0-ONNX`, verifies its SHA-256, stages it under
`library/src/main/assets/voices/`, builds the AAR, and verifies that the final AAR
contains `assets/voices/af_heart.bin`.

Expected SHA-256:
    d583ccff3cdca2f7fae535cb998ac07e9fcb90f09737b9a41fa2734ec44a8f0b

## Android system TTS comparison bug fixed

System voices are now ranked:
1. en-IN
2. en-US
3. en-GB
4. any other English voice
5. non-English fallback

The replaceable TtsEngine architecture is unchanged.
