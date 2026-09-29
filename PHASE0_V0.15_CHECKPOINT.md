# Phase 0 v0.15 — Kokoro Performance Lab

This checkpoint supersedes v0.14. It batches the remaining Kokoro performance
questions into one automated lab while preserving the clean main UI.

## Carried forward
- Kokoro FP32 quality baseline
- clean Quality / Experimental / System UI
- ARM64-only APK
- ONNX Runtime 1.30
- ALL_OPT CPU profiles
- token-aware long-text segmentation
- conservative 500 phoneme-token chunk ceiling
- sentence/newline-aware splitting
- clarified PSS / RSS / Java heap / native allocation diagnostics

## New: optional FP16 model slot
Bolo keeps the existing FP32 model in its original storage location and adds a
separate private FP16 slot.

Official v1.0 FP16 file:
- `onnx/model_fp16.onnx`
- SHA-256:
  `ba4527a874b42b21e35f468c10d326fdff3c7fc8cac1f85e9eb6c0dfc35c334a`

The app still has no network permission. The user downloads the model externally
and imports it through Android's document picker.

## One-tap Performance Lab
`Run full Kokoro suite` performs:

Quick matrix:
- FP32 × CPU 2 / 4 / 6 / 8
- FP16 × CPU 2 / 4 / 6 / 8 when FP16 is installed
- one fixed Narration passage at 1.00x synthesis speed
- engine init time
- generation/audio duration and RTF
- process RSS / PSS / native PSS
- battery temperature and thermal state
- failures are recorded instead of aborting the whole matrix

Winner validation:
- fastest successful quick-matrix configuration
- 5x Narration stress test
- long-form synthesis using the long-text chunker
- final RSS / PSS / temperature / thermal state
- representative winner audio remains available to Play after the lab

Safety / repeatability:
- XNNPACK is not part of the suite; it was already rejected on the Vivo
- thread count is the only runtime variable inside one precision
- short pause + session release + GC between configurations
- severe/critical/emergency/shutdown thermal states stop further matrix work
- per-short-run timeout: 180 s
- long validation timeout: 600 s

## Decision produced by this checkpoint
One install can answer:
1. best FP32 CPU thread count
2. whether FP16 works on the Android ORT path
3. whether FP16 is actually faster
4. rough resident-memory difference
5. thermal behavior across profiles
6. repeated synthesis stability
7. long-text chunking stability
8. the fastest measured Kokoro configuration on this phone

After this checkpoint, the next build should move to the streaming / rolling
buffer player rather than adding more manual benchmark controls, unless every
FP16 configuration fails.
