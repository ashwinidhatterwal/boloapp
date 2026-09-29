# Bolo v0.17 — Kokoro Reader Prototype

Decision:
Kokoro is now the sole narration engine for the prototype. Supertonic, Pocket,
Kitten and Android system-TTS comparison paths are removed from the active app.

This checkpoint stops behaving like a benchmark app and starts behaving like a
reader.

Included:
- Kokoro FP32
- fixed `ALL_OPT` / up-to-8-thread runtime profile
- upstream-wrapper token-aware long-text protection
- reader-level sentence/paragraph segmentation
- rolling segment generation
- dynamic Media3 playlist playback
- playback speed: 1.0x / 1.25x / 1.5x / 1.75x / 2.0x
- TTS synthesis speed stays at 1.0x; player speed is separate
- local reusable narration cache keyed by model + voice + text
- 512 MB LRU-style prepared-audio ceiling for this prototype
- small startup buffer followed by ~45 seconds of target listening reserve
- generation sleeps whenever the rolling reserve is full
- severe Android thermal states pause new synthesis while cached audio continues
- playback-gap counter
- compact hidden details panel

Important limits of this prototype:
- Kokoro remains close to real-time generation on the tested Vivo. At 1.5x and
  faster, a sufficiently long uncached book can still consume the reserve
  because generation is slower than playback.
- Cached/pre-generated audio has no TTS throughput limitation.
- WAV cache is intentionally simple and large. A production version should
  encode prepared audio more compactly.
- No EPUB/PDF import yet. This checkpoint validates the reading scheduler first.

Next checkpoint if this is stable:
- EPUB import
- chapter model
- exact resume
- background/screen-off Media3 service
- headset controls
- chapter-aware pre-generation
- Narration Director V1
