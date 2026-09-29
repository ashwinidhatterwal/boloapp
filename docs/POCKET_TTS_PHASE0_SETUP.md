# Pocket TTS Phase-0 setup

Bolo v0.8 benchmarks Pocket TTS through the Android system TTS interface.
Pocket runs in a separate app/service, so its native runtime is isolated from
Bolo while still implementing the same Bolo `TtsEngine` benchmark contract.

## One-time setup

1. Install Pocket TTS Android Engine v0.5.2 (ARM64).
2. Download `PocketTTS-english-FP32.zip`.
3. Open the Pocket TTS app.
4. Import the English FP32 ZIP as its language pack.
5. Confirm the English voice appears in Pocket TTS.
6. Return to Bolo.
7. Select `Pocket`.
8. Run Narration -> Generate -> Play.
9. Run 5x stress.
10. Compare with Kokoro FP32 on the same text.

Stable comparison assets used for this phase:

- Engine release: v0.5.2
- APK SHA-256:
  `59ac38371db5d9ddee08a7e28775d7b6af2b53b73e49dcfc99e80f62f1bd033e`
- English FP32 pack SHA-256:
  `f2027032434a8616855c12f505fec8864bcbaccd42de43bdf69bff340fb411c4`

## Benchmark interpretation

Use the same Narration passage at 1.00x for both engines.

Important numbers:
- voice naturalness
- generation time
- audio duration
- RTF
- process PSS
- native PSS
- battery temperature
- thermal status

Target for a strong audiobook engine:
- RTF <= 0.7 preferred
- natural long-form voice
- no progressive thermal or memory growth

## Safety timeout

Every Bolo synthesis pass has a 60-second timeout in v0.8.
Pocket and Android system TTS can also be explicitly cancelled through their
service stop path.
