# Bolo v0.23 — Natural Narrator

This checkpoint replaces the fixed-pause sentence-by-sentence narration path.

## What changed

- **Punctuation-preserving Kokoro G2P:** commas, sentence punctuation, ellipses,
  em dashes and quotation marks are reinserted into the phoneme stream before
  Kokoro tokenization. Decimal points, numeric commas/colons and common
  abbreviation periods are protected.
- **Semantic batching:** Bolo keeps sentence-level source locations for line
  selection/highlighting, but up to four compatible sentences (~520 spoken
  characters) share one model call. Batches never mix explicit speakers or
  cross paragraph/scene/chapter boundaries.
- **Measured pacing:** Bolo measures Kokoro's real PCM trailing silence and only
  adds or trims silence when it falls outside a natural boundary range.
- **Boundary targets:** ordinary sentence ~210 ms, question/exclamation ~245 ms,
  hesitation ~360 ms, interruption ~90 ms, paragraph ~455 ms, scene ~850 ms,
  chapter ~1100 ms. These are total tail targets, not extra fixed pauses.
- **Book structure:** EPUB/HTML paragraph boundaries are respected; new EPUB
  imports preserve blank-line block structure; scene markers such as `* * *`
  and `— — —` are not spoken aloud.
- **Subtle character performance:** explicit characters use a light (~14%) blend
  of their persistent character voice into the chosen narrator instead of a
  hard switch to a completely different voice.
- **Reader controls preserved:** chapters, tappable sentence lines, huge-book
  navigation, resume and Media3 background playback continue to use exact
  source-word locations.
- **Cache generation bumped** to `kokoro-reader-v3-natural-narrator`, so old
  v0.22 fixed-pause audio is not reused.
- **Safer reserve:** 1x now starts around 20 s prepared and targets ~90 s; fast
  playback gets progressively larger reserves.

## Performance intent

v0.22 paid ONNX startup/dispatch overhead once per sentence. v0.23 amortizes
that overhead across several compatible sentences. Actual RTF must be measured
on the target Vivo; no device speed claim is made before testing.
