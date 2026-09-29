# Bolo v0.22 — Reader Precision + Polished Player

This checkpoint focuses on narration correctness and direct reading control.

## Sentence and punctuation precision

- Replaced the old regex sentence splitter with a deterministic scanner.
- Narration units no longer batch neighboring sentences together.
- Protects common abbreviations (`Dr.`, `Mr.`, `e.g.`, initials, acronyms) and decimal points.
- Handles `.`, `?`, `!`, ellipsis, Devanagari danda, and common CJK terminal punctuation.
- Dialogue punctuation followed by an attribution stays structurally coherent.
- Oversized sentences split at clause boundaries before falling back to whitespace.
- Soft single line-wraps are folded back into a sentence; real paragraph/heading boundaries remain separate.
- Unmatched quotes fall back to narrator text instead of swallowing the remainder of a chapter.
- Punctuation outside a closing quote is kept with dialogue so word-location offsets stay monotonic.

## Audible punctuation pauses

Every newly generated sentence receives real PCM silence after synthesis:

- question/exclamation: about 260 ms
- period/ellipsis/danda: about 220 ms
- semicolon/colon: about 150 ms
- comma: about 105 ms
- paragraph boundary: at least 310 ms

The silence is appended directly to the generated PCM WAV, so Media3 cannot run two sentences together even if playlist items are gapless. Cache keys are versioned so older pause-less narration is not reused.

## Selectable chapter text

- The active chapter is parsed into sentence-level selectable lines.
- Current line is highlighted and automatically kept in view while narration advances.
- Tapping a line stops the current queue and starts narration from that sentence.
- Line positions use the same word-offset model as book progress, page jumps and resume.
- Chapter text is loaded lazily; only the current chapter/section is held for the reading panel.

## Player redesign

- Current chapter/section is now a prominent selector in the player.
- Shows `Chapter X of N` plus the actual chapter title.
- Tapping the chapter title opens a searchable chapter sheet.
- Chapter sheet supports title search or direct chapter-number search.
- Previous / Play-Pause / Next chapter controls are arranged like a media player.
- Current sentence preview and line number are visible in the player.
- Speed, Stop and narrator controls remain available without cluttering the main transport row.
- Whole-book scrubber, ±50 pages and Go to page remain below the reading panel.

## Tests added

- abbreviation + decimal sentence boundaries
- dialogue attribution boundaries
- paragraph/soft-wrap behavior
- Devanagari danda boundaries
- line word-offset monotonicity
- no neighboring-sentence batching
- punctuation pause policy
- dialogue word-location coverage
- synthetic WAV pause-appending validation

GitHub artifact: `bolo-v0.22-reader-precision-player`
APK: `Bolo-v0.22-Reader-Precision-Player.apk`
