# Bolo v0.25 — Audiobook Compiler

v0.25 changes Bolo's product architecture from live rolling neural narration to
prepare-ahead chapter compilation. Kokoro remains the voice engine; Bolo now does
the preparation, contextual performance planning, quality control and mastering
before playback.

## Why this checkpoint exists

Real-device testing showed that continuous Kokoro inference is too power hungry
and only barely sustainable around 1× on the target phone. More buffering cannot
solve that fundamental compute cost. The new rule is: expensive synthesis happens
before listening; listening is normal cached Media3 playback.

## Narration changes

- The entire chapter is analysed before Kokoro is called.
- Kokoro chunk target changed from v0.24's ~380-token strategy to a quality-first
  target of 165 tokens, ceiling 225. Short utterances are bundled with useful
  nearby context where possible.
- Long source units are fragmented at punctuation/clause/whitespace boundaries
  instead of falling toward Kokoro's long-context rushing zone.
- Quote spans no longer create fake paragraph boundaries merely because the
  temporary analysis span ended.
- Dialogue + attribution may share a model call when this avoids an unnaturally
  tiny utterance.
- Performance state is chapter/scene-aware and uses gradual energy, tension and
  warmth rather than isolated sentence labels.
- Speech verbs/adverbs are treated as evidence, but delivery manner is separated
  from emotion: whisper != tender and shout != angry unless context supports it.
- Low-confidence emotion falls back toward neutral.
- Synthesis-speed changes are intentionally tiny: 0.972–1.025.
- Character timbre switching is disabled in the quality compiler. One consistent
  narrator is the default.

## Automatic quality control

Newly generated PCM is inspected before caching. QC detects gross failures such
as near-silence, implausible duration per word, clipping and very long internal
silence. A suspicious chunk receives one neutral-speed retry; the better-scoring
render is kept. Retry count is visible in Reader details.

## Prepare-ahead execution

Pressing Play on unprepared content now compiles the remainder of the selected
chapter first. The player is populated during compilation but does not start
until chapter compilation is complete. Once playback begins, Kokoro is idle.

When a prepared chapter ends, Bolo advances to the next chapter and waits for the
next explicit Play/compile action rather than silently starting expensive neural
work while the user is listening.

This is an intermediate infrastructure checkpoint. The next prepare-ahead step
is background/charging compilation of a selected horizon or whole book and
speech-optimized compressed prepared-audio storage instead of long-term WAV.

## Maintainability

`NARRATION_COMPILER_DESIGN.md` is the narration quality contract. It records the
human-narration and long-form-TTS principles behind the implementation so future
changes do not drift back toward arbitrary pause tuning.
