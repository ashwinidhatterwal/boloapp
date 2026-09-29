# Phase 0 v0.14 — Kokoro CPU tuning checkpoint

This checkpoint supersedes the unpushed v0.13.1 package.

Carried forward:
- clean v0.12 benchmark UI
- Kokoro FP32 quality baseline
- token-aware long-text segmentation
- 500-token conservative per-inference ceiling
- sentence/newline-aware chunking
- one-WAV reassembly for benchmark output
- persistent runtime profile selection

Removed:
- XNNPACK 4/6/8 profiles
  (measured ~RTF 2.0 on the Vivo I2302 and rejected)

CPU tuning profiles:
- Baseline: BASIC_OPT, up to 4 threads
- CPU 2: ALL_OPT, 2 threads
- CPU 4: ALL_OPT, 4 threads
- CPU 6: ALL_OPT, 6 threads
- CPU 8: ALL_OPT, 8 threads

All ALL_OPT profiles use sequential execution, inter-op 1 and disabled thread
spinning. Thread count is intentionally the only variable in this checkpoint.

Known prior Vivo result:
- v0.13 CPU optimized / 8-core adaptive
- 5x stress mean RTF: ~0.894
- best / worst: ~0.853 / 0.910

Diagnostics now distinguish:
- Process PSS: proportional resident memory
- Native PSS: native proportional resident memory
- Process RSS: resident set from /proc/self/status
- Java heap used
- Native allocated: allocator accounting, explicitly not labelled as RSS
- available system RAM
- battery temperature / thermal status

Next decision:
Choose the fastest stable CPU thread count. Then test Kokoro FP16 on that exact
runtime profile. Do not mix the FP16 experiment with thread-count selection.
