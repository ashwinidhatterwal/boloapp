# Phase 0 v0.13 — Kokoro runtime profile lab

Goal: determine whether Kokoro FP32 can gain enough Android throughput for
continuous buffered narration and faster playback without changing the model.

The clean v0.12 main UI is preserved. Runtime controls live only under Advanced
when Quality/Kokoro is selected.

Profiles:
1. CPU baseline
   - BASIC_OPT
   - sequential
   - up to 4 ORT intra-op threads
2. CPU optimized
   - ALL_OPT
   - sequential
   - up to 8 ORT intra-op threads
   - ORT thread spinning disabled
3. XNNPACK 4
4. XNNPACK 6
5. XNNPACK 8
   - ALL_OPT
   - ORT intra-op = 1
   - XNNPACK private thread pool = selected size (capped at device cores)
   - ORT intra/inter-op spinning disabled
   - normal CPU fallback remains available for unsupported nodes

The selected profile is stored in SharedPreferences and survives app restarts.

Test protocol:
- Keep the same Kokoro FP32 model.
- Voice af_heart.
- Speed 1.00x.
- Narration passage.
- Generate once, then run 5x stress.
- Let the phone cool between profiles if temperature rises materially.

Targets:
- RTF < 1.0 = generation can keep up with 1x playback in steady state.
- RTF <= 0.67 = theoretical steady-state requirement for 1.5x playback.
- RTF <= 0.55-0.60 = preferred safety margin for 1.5x playback plus Android
  background/thermal variation.

This phase changes runtime configuration only. Narration Director, book parsing,
buffering and reader features remain out of scope until the fastest stable
Kokoro profile is known.
