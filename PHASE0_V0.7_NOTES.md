# Phase 0 v0.7 — ONNX Runtime lab

Purpose: test whether the q8f16 crash and Kokoro speed are materially affected by
the runtime rather than the app architecture.

Changes:
- ONNX Runtime Android upgraded from 1.20.0 to 1.30.0.
- Kokoro wrapper remains pinned to upstream commit:
  593e2353954498b667e3bf3489a61723e6ba6b59
- Kokoro session profile:
  - BASIC_OPT
  - SEQUENTIAL execution
  - intra-op threads = min(4, device cores)
  - inter-op threads = 1
  - CPU arena enabled
  - memory-pattern optimization enabled
- SessionOptions stay alive for the session lifetime and close on release.
- Imported model metadata records original filename, SHA-256, and size.
- UI shows actual ORT version, runtime profile, and engine initialization time.
- Memory diagnostics now separate process PSS, native PSS, and native heap allocated.
- System TTS English-first ordering from v0.6 remains.
- q8f16 is NOT assumed fixed; this build is specifically to test it under a newer,
  less aggressive runtime/session path.

Recommended test order:
1. q8f16 Generate once.
2. If it works, run 5x stress.
3. Replace with FP32 and run Generate + 5x stress.
4. Compare RTF, process/native PSS, native heap, temperature, and voice quality.
