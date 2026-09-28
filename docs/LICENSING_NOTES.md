# Licensing Notes — Prototype Only

This project is an engineering benchmark, not the final production dependency set.

## Kokoro

Kokoro-82M model weights are published under Apache-2.0 by the upstream project.

The Phase-0 app currently uses the third-party `kokoro-android` AAR only because it
lets us measure real Android performance quickly. That wrapper states Apache-2.0 for
its code and bundles/uses espeak-ng as a separate GPL-3.0 shared library.

Before a public commercial release we must perform a full dependency and distribution
audit and may replace this adapter with a different implementation (for example a
sherpa-onnx/native adapter). The app architecture is specifically designed to make
that replacement local to the TTS module.

## Pocket / Kitten

Do not add weights merely because source code is permissively licensed. Audit source,
model weights, voices, phonemizer and redistribution terms separately.
