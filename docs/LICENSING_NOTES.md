# Licensing Notes — Prototype Dependency Audit

## Kokoro

Kokoro-82M model weights are published under Apache-2.0 by the upstream project.

The prototype currently uses the third-party `kokoro-android` AAR to run the
model on Android. That wrapper states Apache-2.0 for its code and uses espeak-ng
as a separate GPL-3.0 component. Before public commercial release, audit source,
model weights, voices, phonemizer and distribution obligations together.

## PdfBox-Android

Text-based PDF import uses `com.tom-roush:pdfbox-android:2.0.27.0`. The project
states Apache-2.0 for its main code. Bolo uses it only for local PDF text and
metadata extraction; optional image/JPX add-ons are not included in v0.20.

## General rule

Do not infer model or asset redistribution rights from source-code licenses.
Production release review must cover every native library, model weight, voice
pack and bundled data file separately.
