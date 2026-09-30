# Bolo Audiobook Compiler — Narration Standard

This document is the quality contract for Bolo's prepare-ahead narration path.
Future changes should preserve these principles unless a listening test proves a
better alternative.

## Current implementation (v0.26)

Durable manifests, compressed prepared audio, resumable scheduled preparation,
stable seeking and an optional evidence-validated semantic director are now
implemented. See BOLO_V0.26_CHECKPOINT.md for the current contract and limits.

## Product decision

Bolo is no longer designed around continuous neural TTS during listening.
Kokoro is the render engine; Bolo is the narrator/director/compiler.

The desired pipeline is:

`book -> structural analysis -> chapter/scene context -> dialogue/character state -> restrained performance plan -> quality-range Kokoro chunks -> acoustic QC/retry -> boundary mastering -> persistent prepared audio -> low-power Media3 playback`

The player must never require continuous Kokoro inference once prepared audio is
playing.

## Evidence we are designing around

### Human audiobook practice

Audible/ACX guidance and interviews with professional narrators repeatedly
emphasize:

- prepare before recording; understand the book and characters first;
- pacing, silence and breathing are part of the performance;
- let the text breathe, but do not give every sentence equal dramatic weight;
- character differentiation should be understated rather than cartoonish;
- attitude, rhythm and tempo matter more than crude pitch changes;
- genre and scene affect pace;
- proofing/QC must check pacing, exact text, pronunciation and consistency.

References:
- ACX, “More Production Pointers from Audible Approved Producers”
  https://www.acx.com/mp/blog/more-production-pointers-from-audible-approved-producers
- ACX, “How to Act Like an Audiobook Narrator”
  https://www.acx.com/mp/blog/how-to-act-like-an-audiobook-narrator
- ACX, “5 Tips for Choosing a Narrator”
  https://www.acx.com/mp/blog/5-tips-for-choosing-a-narrator
- ACX, “How To Review Your Final Audio The Audible Studios Way”
  https://www.acx.com/mp/blog/how-to-review-your-final-audio-the-audible-studios-way

### Long-form speech research

Long-form TTS research consistently finds that sentence-isolated synthesis loses
cross-sentence prosody and discourse coherence. Useful context includes paragraph
semantics, prior conversational context and sentence position.

References:
- ParaTTS (2022): https://arxiv.org/abs/2209.06484
- ContextSpeech (2023): https://arxiv.org/abs/2307.00782
- Long-Context Speech Synthesis with Context-Aware Memory (2025):
  https://arxiv.org/abs/2508.14713
- Emotional-Context-Speech / context-aware emotional TTS (ACL 2026):
  https://aclanthology.org/2026.findings-acl.940/
- LibriQuote / computational narrative understanding for expressive TTS
  (ACL 2026): https://aclanthology.org/2026.findings-acl.308/

The LibriQuote work is particularly relevant to fiction: speech verbs and adverbs
around quotations carry useful delivery information such as whispered softly,
shouted angrily, etc.

### Kokoro-specific behavior

Kokoro's own voice documentation says most voices are strongest in a
“goldilocks” range of roughly 100–200 tokens out of about 500 possible. Very
short utterances (especially below about 10–20 tokens) can be weaker, while very
long utterances (especially above ~400) can rush. The upstream English tokenizer
also searches punctuation boundaries when it must split long text.

References:
- Kokoro VOICES.md:
  https://huggingface.co/hexgrad/Kokoro-82M/blob/main/VOICES.md
- Kokoro pipeline:
  https://github.com/hexgrad/kokoro/blob/main/kokoro/pipeline.py
- Kokoro demo notes: punctuation can be used to influence intonation:
  https://github.com/hexgrad/kokoro/blob/main/demo/app.py

## Bolo narration rules

### 1. Analyse large context; synthesize medium context

The entire chapter is planned before the first neural render call. Scene state,
dialogue continuity and nearby attribution are therefore available to the
planner.

Kokoro normally receives approximately 100–200-token chunks, not an entire
paragraph just because the model technically accepts more. The current compiler
target is 165 tokens with a quality-first ceiling of 225.

Short dialogue such as “No.” should be bundled with useful nearby context when
possible rather than synthesized alone.

Long source sentences are divided only at linguistic boundaries such as sentence
punctuation, semicolons, colons, commas or em dashes before falling back to a
whitespace cut.

### 2. Author punctuation remains the primary performance instruction

Bolo preserves punctuation into Kokoro's phoneme/token stream. It normalizes
obvious typographic equivalents but must not casually rewrite meaning.

Do not add exclamation marks, questions or ellipses merely to force emotion.

### 3. Emotion is confidence-gated

Bolo would rather underplay than confidently perform the wrong emotion.

Evidence strength, in descending order:

1. explicit speech verb/adverb and nearby emotional language;
2. sustained local scene/action evidence;
3. punctuation alone.

Punctuation alone never justifies a strong emotional label.

Important distinction: *manner is not emotion*. “whispered” means low-energy
speech; it does not automatically mean tenderness. “shouted” means high-energy
speech; it does not automatically mean anger.

### 4. Performance has inertia

Energy, tension and warmth change gradually across a scene. A single word should
not make the narrator jump from neutral to melodramatic and immediately back.
Scene/chapter breaks pull state back toward neutral.

### 5. Acoustic control stays subtle

Kokoro does not expose a rich semantic emotion-control interface. Bolo therefore
uses tiny synthesis-speed changes only, currently clamped to 0.972–1.025.
Listener playback speed remains completely separate.

Character timbre switching is disabled by default in the quality compiler.
The optional semantic director may adjust bounded gain and structural pauses
as well as speed; it cannot rewrite author text or create full emotional acting. One
consistent narrator is preferred over abrupt synthetic voice changes.

### 6. Boundaries are hierarchical

Inside a Kokoro chunk, punctuation provides the natural micro-rhythm.
Between chunks Bolo normalizes only the actual acoustic silence and uses stronger
spacing for stronger document structure:

`continue < sentence < paragraph < scene < chapter`

An interruption/em dash should generally be tighter than a normal completed
sentence; an ellipsis/hesitation may breathe longer.

### 7. Temporary parser spans are not document structure

Quoted dialogue is temporarily split from attribution for analysis. The end of a
quote span must never be treated as a paragraph break unless the source document
actually contains a paragraph break there.

This invariant exists because v0.24 could insert fake paragraph-strength pauses
between a quote and its attribution.

### 8. Automatic QC before the listener hears audio

Prepare-ahead generation enables silent retries. Each newly generated PCM chunk
is checked for:

- missing/invalid output;
- implausible duration per word;
- nearly silent output;
- clipping;
- extreme internal silence.

A suspicious chunk receives one conservative re-render at neutral synthesis
speed. The better-scoring take is kept. There is deliberately no infinite retry
loop.

Future QC should add pronunciation/outlier checks and chapter-level loudness
normalization.

### 9. Plan validation precedes synthesis

The compiler verifies:

- source-word location accounting is preserved;
- no batch exceeds Kokoro's hard context;
- chunk token statistics are available for diagnostics;
- structural boundaries are not crossed accidentally.

### 10. Playback is not synthesis

v0.25 compiles the remainder of the selected chapter first. Only after the
chapter is prepared does playback start. Kokoro is idle while that prepared
chapter is playing.

v0.26 implements selected-chapter, next-three and remaining-book preparation,
charging constraints, durable per-chunk checkpoints and compressed M4A storage.
Completed audio is user-owned and is not silently evicted.

## What “emotionally correct” means for Bolo

The target is a restrained professional audiobook read, not exaggerated voice
acting. Correctness is more important than intensity.

- explicit evidence -> small confident performance adjustment;
- ambiguous evidence -> neutral delivery;
- character identity -> continuity and conversational context first;
- scene tone -> gradual state, not per-sentence label flipping;
- dramatic punctuation -> respect the author's writing;
- unknown intent -> do not invent it.

## Regression test philosophy

Every narration change should be checked against a permanent fiction torture
suite containing at minimum:

- quote + attribution on the same line;
- rapid alternating dialogue;
- unattributed dialogue with ambiguous speakers;
- whisper/shout without emotional context;
- explicit angry/fearful/tender attribution;
- ellipses and interruptions;
- long multi-clause sentences;
- abbreviations/initials/decimals;
- scene separators;
- chapter transitions;
- very short dialogue;
- very long source sentences;
- source-word accounting and seek/highlight anchors.

No new feature is allowed to improve one sample by breaking these classes of
text.
