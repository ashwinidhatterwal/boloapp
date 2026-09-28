# Offline Natural Audiobook Reader
## Product & Engineering Blueprint
**Working concept:** Unlimited, natural, audiobook-style text-to-speech that can run entirely on the user's device.

**Document status:** Build blueprint v1.0  
**Date:** 28 September 2026  
**Primary platform:** Android  
**Core principle:** Normal reading must not create a per-minute cloud/API cost.

---

# 1. Product Vision

Build an Android reading app that can turn very large books and documents—including multi-thousand-page novels—into natural, continuous, audiobook-style speech while remaining usable offline.

The goal is **not** to make another basic "paste text and press speak" application.

The product should behave like an **offline audiobook narration system**:

- Import a book or document.
- Start listening quickly.
- Keep narration consistent across chapters.
- Pronounce names correctly.
- Detect dialogue and narration.
- Use sensible pauses, pacing and emphasis.
- Handle Hindi, English and eventually Hinglish intelligently.
- Remember reading position and book-specific pronunciations.
- Generate audio ahead of playback.
- Avoid generating the entire audiobook in advance.
- Work without an internet connection after required models are installed.
- Allow effectively unlimited listening without creating a TTS bill for us.

The app's long-term differentiator should be the **Narration Engine**, not any single third-party TTS model.

---

# 2. Core Product Promise

The product should eventually be able to truthfully offer:

> **Import it. Listen naturally. Stay offline. No listening credits.**

The core free/local experience should not have:

- per-character limits;
- monthly listening-hour limits;
- mandatory accounts;
- required cloud processing;
- recurring server inference costs;
- forced upload of the user's books.

Internet may optionally be used for:

- downloading voice/model packs;
- app updates;
- optional cloud-quality voices in the future;
- optional metadata lookup;
- optional backup/sync.

None of these should be required for ordinary local reading.

---

# 3. Product Principles

## 3.1 Offline First

All core reading functions should work without internet after installation of the required voice pack.

Offline components include:

- book parsing;
- text normalization;
- narration analysis;
- pronunciation dictionary;
- TTS inference;
- audio buffering;
- audiobook playback;
- bookmarks;
- reading history;
- book memory;
- local search.

## 3.2 Model-Agnostic Architecture

Never make the app dependent on one TTS model.

Create a common internal interface:

```text
TtsEngine
 ├── initialize()
 ├── supportedLanguages()
 ├── supportedVoices()
 ├── synthesize()
 ├── stream()
 ├── cancel()
 ├── memoryEstimate()
 └── release()
```

Possible engines can then include:

- Kokoro-based ONNX engine;
- Pocket TTS;
- KittenTTS;
- future open models;
- platform Android TTS as emergency fallback;
- optional cloud engine later.

The rest of the application must not care which engine produced the audio.

## 3.3 Narration Before Synthesis

Raw text should rarely be sent directly to the TTS engine.

Pipeline:

```text
Document
   ↓
Document Parser
   ↓
Canonical Text
   ↓
Text Normalizer
   ↓
Narration Director
   ↓
Pronunciation / Language Layer
   ↓
Speech Segmentation
   ↓
TTS Engine
   ↓
Audio Post-Processor
   ↓
Cache
   ↓
Player
```

## 3.4 Progressive Processing

A 5,000-page novel must not require full preprocessing or full audio generation before the user can listen.

The system should:

1. parse enough to build the book structure;
2. prepare the first chapter;
3. begin playback;
4. analyze/generate upcoming content in the background;
5. keep a rolling buffer ahead of the listener.

## 3.5 User Control Without Complexity

Expose simple controls:

- Natural
- Calm
- Dramatic
- Fast
- Voice
- Speed

Do not expose dozens of model-specific parameters to normal users.

Advanced controls can live behind an "Advanced narration" section.

---

# 4. Target Users

## Primary

### Long-form readers
People who want books, EPUBs, PDFs, articles or study material read aloud.

### Users who prefer listening
People commuting, exercising, doing housework, resting their eyes, or multitasking.

### Indian multilingual users
Users who move between Hindi, English and mixed-language content.

### Accessibility users
People with low vision, reading fatigue, dyslexia or other reasons to prefer listening.

## Secondary

- students;
- researchers;
- professionals reading reports;
- creators who need quick narration;
- elderly users;
- users who want offline/private document reading.

---

# 5. Product Scope

## Version 1 supported inputs

Prioritize:

1. plain pasted text;
2. TXT;
3. EPUB;
4. PDF with embedded text.

Then add:

5. DOCX;
6. webpage/share-to-app;
7. scanned PDF;
8. image/photo OCR.

EPUB should be a particularly important format because novels are structurally cleaner than arbitrary PDFs.

## Output

Primary output:

- continuous in-app listening.

Secondary output:

- temporary cached speech segments.

Later:

- export chapter audio;
- export an entire audiobook;
- M4B/MP3/Opus export;
- chapter metadata.

---

# 6. Android Technical Foundation

Recommended base:

- **Language:** Kotlin
- **UI:** Jetpack Compose
- **Architecture:** modular MVVM / clean architecture
- **Async:** Kotlin Coroutines + Flow
- **Database:** Room / SQLite
- **Playback:** Android Media3
- **Background work:** WorkManager where appropriate
- **Foreground playback service:** Media3 playback service
- **TTS inference:** ONNX Runtime or model-specific native runtime
- **Native performance layer:** C++/NDK only where required
- **Dependency injection:** Hilt or Koin
- **Serialization:** Kotlin serialization
- **Preferences:** DataStore

Avoid unnecessary backend infrastructure in the initial version.

---

# 7. Proposed Module Layout

```text
app/
core/
    common/
    database/
    filesystem/
    audio/
    models/
    settings/

document/
    import/
    epub/
    pdf/
    text/
    docx/
    ocr/

narration/
    normalization/
    language/
    dialogue/
    characters/
    pronunciation/
    prosody/
    segmentation/
    bookmemory/

tts/
    api/
    kokoro/
    pocket/
    kitten/
    androidfallback/

synthesis/
    scheduler/
    queue/
    cache/
    postprocessing/

player/
    service/
    controls/
    progress/
    bookmarks/

feature/
    library/
    reader/
    player/
    voices/
    settings/
    pronunciation/
    diagnostics/
```

This modularity is important because speech models will change over time.

---

# 8. Core Data Model

## Book

```text
Book
- id
- title
- author
- sourceUri
- sourceType
- importedAt
- languageHints
- cover
- totalCharacters
- totalWords
- currentPosition
- activeNarrationProfileId
```

## Chapter

```text
Chapter
- id
- bookId
- index
- title
- startOffset
- endOffset
- wordCount
- processingState
```

## Speech Segment

```text
SpeechSegment
- id
- bookId
- chapterId
- sequence
- rawText
- normalizedText
- speakerId
- language
- narrationStyle
- pronunciationOverrides
- synthesisHash
- audioCachePath
- duration
- state
```

## Character

```text
Character
- id
- bookId
- canonicalName
- aliases
- firstSeenChapter
- voiceProfile
- confidence
- manuallyEdited
```

## Pronunciation

```text
PronunciationEntry
- id
- scope
- bookId?
- language
- writtenForm
- phoneticForm
- userConfirmed
- source
```

Scopes:

- global;
- language;
- book;
- user.

## Playback Position

Store precise:

- book;
- chapter;
- segment;
- audio offset;
- text offset.

This allows reliable resume even if cached audio is later deleted.

---

# 9. Book Import Pipeline

## Step 1 — Copy or Safely Reference File

Use Android Storage Access Framework.

Do not rely on unstable external paths.

## Step 2 — Detect Format

Identify:

- EPUB;
- PDF;
- TXT;
- DOCX;
- image/scanned document later.

## Step 3 — Extract Canonical Text

Canonical representation should preserve:

- chapter boundaries;
- paragraph boundaries;
- headings;
- quotations;
- emphasis where available;
- ordered content;
- source offsets.

## Step 4 — Build Structural Index

Do not deeply analyze the entire document yet.

Create:

- chapters;
- paragraphs;
- text offsets;
- approximate word count;
- estimated listening duration.

## Step 5 — Prepare Immediate Listening Window

Analyze only:

- current chapter;
- next chapter or two;
- limited context.

Playback should become available as soon as enough initial audio exists.

---

# 10. Handling a 5,000-Page Novel

This is a first-class requirement.

Never create one huge audio file.

A huge novel should behave like this:

```text
Book imported
    ↓
Structure indexed
    ↓
Chapter 1 analyzed
    ↓
first speech chunks generated
    ↓
playback starts
    ↓
next chunks generated continuously
```

## Processing Window

Maintain approximately:

- previous 5–15 minutes;
- current playback segment;
- next 20–60 minutes.

The exact buffer should adapt to device performance.

## Cache Policy

Modes:

### Minimal
Keep only enough audio for reliable playback.

### Balanced
Keep the current chapter and one or two upcoming chapters.

### Keep Played Audio
Do not automatically delete played segments.

### Full Audiobook
Generate and retain everything.

Default should be Balanced.

## Why this scales

A 5,000-page book may be huge in reading time, but the active synthesis workload remains small because inference is performed on a sliding window.

Book length affects:

- database size;
- index size;
- total eventual generation time.

It should not significantly affect:

- current RAM requirements;
- current model context size;
- UI responsiveness.

---

# 11. Narration Director

This is the central product technology.

The Narration Director converts text into a structured speaking plan.

Input:

```text
previous context
current paragraph
next context
book memory
narration mode
language information
```

Output concept:

```text
speaker = narrator
pace = 0.96
energy = calm
pause_before = 100ms
pause_after = 420ms
emphasis = [word ranges]
language = en
```

For dialogue:

```text
speaker = character_12
delivery = uncertain
pace = 0.91
dialogue = true
```

The implementation should begin rule-first.

Do **not** make a large local LLM mandatory for basic narration.

---

# 12. Narration Director V1 — Deterministic Rules

V1 should recognize:

- punctuation;
- paragraph endings;
- headings;
- quotation marks;
- dialogue tags;
- questions;
- exclamations;
- ellipses;
- em-dashes;
- parentheses;
- lists;
- abbreviations;
- chapter titles.

Examples:

### Question
Slight pitch/prosody hint if engine supports it.

### Exclamation
Moderately increased energy; avoid overacting.

### Ellipsis
Longer contextual pause.

### New paragraph
Longer pause than sentence punctuation.

### Chapter boundary
Clearly longer pause.

### Dialogue
Separate from narration and optionally apply a subtle character voice transformation.

---

# 13. Narration Director V2 — Contextual Intelligence

Later add a small local classifier for:

- emotional class;
- speech act;
- narration vs dialogue;
- dialogue attribution;
- scene intensity;
- pacing.

Possible labels:

```text
neutral
warm
sad
tense
excited
questioning
whisper-like
urgent
reflective
```

The classifier should output metadata, not rewritten prose.

Never allow the director to alter the meaning of a book.

---

# 14. Narration Modes

## Natural — Default

Goal: convincing professional audiobook reading.

- one main narrator;
- subtle dialogue variation;
- moderate expression;
- stable pacing.

## Calm

- slightly slower;
- softer delivery;
- reduced dramatic variation;
- suitable for bedtime.

## Dramatic

- stronger dialogue differentiation;
- larger emotional range;
- optional multiple voices.

## Fast

- minimal deep analysis;
- rapid synthesis;
- optimized for studying/news/documents.

Narration modes must affect metadata, not duplicate the whole application pipeline.

---

# 15. Dialogue Detection

Start with robust rule-based detection.

Recognize:

- `"..."`;
- typographic quotes;
- nested quotation marks;
- em-dash dialogue styles;
- common dialogue tags.

Example:

```text
"Don't move," Sarah whispered.
```

Parse:

```text
speech = Don't move
speaker_candidate = Sarah
delivery_hint = whisper / quiet
narration = Sarah whispered
```

Important:

Do not require speaker attribution to generate audio.

If uncertain:

- use narrator voice;
- avoid inventing a character identity.

---

# 16. Character Memory

Large fiction requires consistent characters.

Create a book-scoped registry.

Example:

```text
Sarah
aliases: Sarah, Sarah Miller
voice_variant: narrator_variant_02
first_seen: chapter 3
confidence: high
```

The system should:

- add characters gradually;
- merge obvious aliases cautiously;
- allow manual correction;
- never change user-confirmed assignments automatically.

## Subtle character voices

Default:

- same core narrator;
- subtle changes in pitch/style/rate.

Avoid cartoonish voice switching.

Optional Dramatic mode may use separate voices.

---

# 17. Text Normalization Engine

This is essential.

Raw written text is not spoken text.

Normalize:

- numbers;
- dates;
- times;
- currency;
- percentages;
- abbreviations;
- acronyms;
- URLs;
- email addresses where relevant;
- units;
- symbols;
- ordinals;
- Roman numerals when context is clear.

Example:

```text
₹1,25,750
```

should become a context/language-appropriate spoken form.

Do not destructively replace the original text.

Store:

```text
rawText
normalizedSpeechText
```

This preserves highlighting and search accuracy.

---

# 18. Hindi and Hinglish Strategy

This deserves its own subsystem.

## Language Identification

Classify at span level, not only paragraph level.

Example:

```text
Kal meeting 10:30 pe hai, please bring the documents.
```

Possible segmentation:

```text
Hindi/Romanized Hindi:
Kal meeting 10:30 pe hai,

English:
please bring the documents.
```

Actual segmentation should consider code-switching and borrowed words.

## Goals

- preserve narrator identity;
- avoid abrupt voice changes;
- pronounce Hindi names correctly;
- speak English words as English when appropriate;
- speak common Indian usage naturally.

## Romanized Hindi

Create a normalization/transliteration layer.

Do not assume every Latin-script word is English.

Potential later enhancement:

- lightweight local Hinglish language model/classifier;
- user correction feedback;
- common Indian word dictionary.

---

# 19. Pronunciation System

Priority order:

1. user-confirmed pronunciation;
2. book-specific pronunciation;
3. language dictionary;
4. model phonemizer;
5. fallback pronunciation.

UI:

```text
Long press word
→ Pronunciation
→ Hear
→ Edit / choose
→ Save for this book
→ Save globally
```

Pronunciation entries should be reusable without modifying source text.

This is especially valuable for:

- Indian names;
- fantasy names;
- place names;
- technical terminology;
- foreign words.

---

# 20. Speech Segmentation

Never synthesize whole chapters as one request.

Segment using semantic boundaries.

Ideal segment size depends on engine.

Rules:

- prefer sentence/paragraph boundaries;
- avoid cutting inside quotation;
- avoid splitting a name/number construct;
- maintain enough context to preserve prosody;
- cap model token/phoneme length.

Each segment gets a deterministic hash based on:

```text
normalized text
voice
speed
narration metadata
pronunciation dictionary version
TTS model version
```

If the hash is unchanged, reuse cached audio.

---

# 21. Context Windows

For each speech segment, the Narration Director may inspect:

```text
previous 1–3 paragraphs
current paragraph
next 1–3 paragraphs
current chapter metadata
known characters
book-specific pronunciation
```

The TTS model itself does not necessarily need all that context.

This allows smarter narration without feeding millions of words into a model.

---

# 22. TTS Engine Strategy

Do not choose the final engine before benchmarking.

Prototype at least three local engine candidates.

## Candidate A — Kokoro family

Strengths:

- small model class;
- strong perceived quality for its size;
- ONNX ecosystem;
- existing Android experimentation;
- permissive Kokoro weights.

Risks:

- phonemizer/dependency licensing must be audited carefully;
- language quality differs;
- some Android wrappers add their own commercial/free limitations;
- wrapper licensing is separate from model licensing.

Important: do not simply embed a third-party wrapper without auditing every dependency.

## Candidate B — Pocket TTS

Strengths:

- compact architecture;
- streaming focus;
- voice features;
- promising CPU performance;
- active development.

Risks:

- Android support may rely on unofficial ports;
- model-weight terms must be reviewed separately from source-code license;
- language support must be verified before committing.

## Candidate C — KittenTTS / similar compact ONNX engines

Strengths:

- very small models;
- mobile-friendly potential;
- fast startup/low storage.

Risks:

- benchmark long-form naturalness;
- ecosystem maturity;
- voice/language coverage.

## Candidate D — Android system TTS

Use only as fallback.

Advantages:

- tiny app footprint;
- no bundled model.

Disadvantages:

- inconsistent quality across devices;
- cannot define product quality.

---

# 23. Engine Benchmark Before Integration

Create a dedicated benchmark application before building the full product.

Test the same passages across every candidate.

Corpus:

### English nonfiction
~1,000 words.

### English fiction
Narration + dialogue.

### Emotional passage
Questions, interruptions, whispers, exclamations.

### Numbers
Dates, prices, times, percentages.

### Indian names
At least 100 common/difficult examples.

### Hindi
Native Devanagari.

### Hinglish
Roman Hindi + English switching.

### Long-run
At least 30 minutes continuously.

Measure:

- time to first audio;
- real-time factor;
- CPU;
- RAM;
- device temperature;
- battery drain;
- audio glitches;
- pronunciation;
- sentence continuity;
- voice consistency;
- subjective naturalness.

---

# 24. Model Selection Gates

Do not choose the production engine unless:

### Quality
Users prefer it or judge it acceptable for at least 20–30 minutes of listening.

### Performance
On target phones, it can maintain enough generation speed to keep the playback queue filled.

### Stability
No crashes during 1+ hour stress test.

### Memory
No destructive background kills under normal device conditions.

### License
Commercial redistribution is clearly understood and acceptable.

### Storage
Model size is acceptable or can be downloaded as an optional pack.

---

# 25. Adaptive Synthesis Scheduler

This is necessary for huge books.

Inputs:

- playback position;
- buffered duration;
- synthesis speed;
- battery level;
- charging state;
- thermal state;
- app background state;
- device RAM;
- user cache preference.

Example policy:

## Normal
Target 20–30 minutes generated ahead.

## Charging + cool device
Target 1–3 chapters ahead.

## Low battery
Target 5–10 minutes ahead.

## High thermal state
Pause speculative generation and preserve only playback continuity.

## User listening fast
Increase synthesis priority.

The listener should not have to manage this.

---

# 26. Thermal and Battery Safety

Do not run maximum inference threads constantly.

Implement:

- configurable worker count;
- thermal listeners;
- battery state listeners;
- generation throttling;
- cooldown;
- pause-on-critical-temperature;
- optional "Only pre-generate while charging."

Show a small status only when useful:

```text
Preparing audio…
Paused to cool device
Ready 24 min ahead
```

Do not overwhelm the UI with technical information.

---

# 27. Audio Cache

Audio files should be individually addressable by segment hash.

Example:

```text
/cache/audio/<book>/<chapter>/<hash>.opus
```

Do not use one giant chapter WAV during live reading.

Recommended internal format:

- PCM only during immediate processing;
- compressed Opus/AAC for retained cache.

Final codec choice should be benchmarked for Android compatibility, decoding cost and quality.

---

# 28. Audio Post-Processing

Keep this conservative.

Possible local operations:

- trim excessive silence;
- add controlled pauses;
- crossfade where appropriate;
- loudness normalization;
- prevent clipping;
- gentle compression;
- resample once;
- remove audible stitching artifacts.

Do not excessively process voices.

The goal is audiobook polish, not a radio effect.

---

# 29. Continuous Playback

Use Media3.

Required:

- lock screen controls;
- Bluetooth/headset controls;
- notification controls;
- Android Auto compatibility later;
- background playback;
- reliable resume;
- audio focus handling;
- sleep timer.

Playback should continue independently of the visible Activity.

---

# 30. Text Highlighting

The current spoken sentence should be highlighted.

Need mapping:

```text
source text offsets
↕
speech segments
↕
audio timing
```

Phase 1 can highlight per sentence/segment.

Later:

- word-level highlighting if engine alignment becomes available.

Never sacrifice stable audio generation just to achieve perfect karaoke-style highlighting in V1.

---

# 31. Reader UI

Main reader screen:

```text
────────────────────────
Book title        ⋮
Chapter title
────────────────────────

Text content...

[current sentence highlighted]

────────────────────────
      -15s   ▶   +15s

     0.9x  1.0x  1.2x

Voice     Natural     Timer

Chapter 12 • 34%
────────────────────────
```

Keep reading controls simple.

---

# 32. Library UI

Library should show:

- cover;
- title;
- author;
- listening progress;
- remaining estimated time;
- downloaded/prepared state.

Actions:

- continue;
- remove;
- clear generated audio;
- edit book information;
- narration settings;
- regenerate current chapter.

---

# 33. Import Experience

After import:

```text
Preparing "Book Name"
✓ Text extracted
✓ 62 chapters found
• Preparing narration…
```

As soon as listening is possible:

```text
Ready to listen
```

Do not make users wait for unnecessary full-book analysis.

---

# 34. Voice Pack System

Models should be downloadable separately.

Example:

```text
Natural English
98 MB
Downloaded

Hindi Natural
142 MB
Download

Experimental Narrator HD
610 MB
Download
```

Benefits:

- smaller initial APK;
- users download only languages they need;
- models can be upgraded independently;
- multiple quality tiers become possible.

Each pack manifest:

```text
id
engine
modelVersion
languages
voices
size
sha256
license
minimumAppVersion
```

Always verify hashes after downloading.

---

# 35. Offline Model Updates

Never silently replace a model while a book is actively cached.

Model version is part of segment cache keys.

If upgraded:

- existing audio can remain;
- future audio uses new model;
- optionally regenerate a chapter;
- avoid voice changing halfway through a paragraph.

User should be informed if a major voice change is expected.

---

# 36. Reliability & Crash Recovery

Every long-running operation must be resumable.

Persist synthesis queue state.

States:

```text
PENDING
ANALYZING
READY_TO_SYNTHESIZE
SYNTHESIZING
CACHED
FAILED
```

If Android kills the process:

- playback state survives where possible;
- queue rebuilds from Room;
- completed segments are not regenerated unnecessarily.

---

# 37. Error Handling

Examples:

### Unsupported PDF
Offer OCR later or explain that the PDF contains images instead of extractable text.

### Model missing
Prompt to download the required voice pack.

### Storage low
Reduce cache automatically and warn.

### TTS failure
Retry segment once/twice, then fallback engine if available.

### Corrupt segment
Delete only the segment and regenerate.

A single failure must never invalidate the entire book.

---

# 38. Privacy

Default design:

- books stay on device;
- narration stays on device;
- listening history stays on device;
- pronunciations stay on device.

No account required.

If optional analytics are later added:

- disclose clearly;
- avoid book text/content collection;
- never transmit the user's reading content for analytics.

This privacy positioning can become part of the product identity.

---

# 39. Security

- use app-private storage for models/cache where appropriate;
- validate imported archives such as EPUB;
- prevent zip-slip;
- limit decompression size;
- sanitize filenames;
- use safe PDF/document parsers;
- do not execute embedded document scripts;
- verify downloaded model checksums;
- use HTTPS for model manifests/downloads.

---

# 40. Licensing Strategy

Licensing must be treated as a release gate.

For every engine maintain a table:

```text
Component
Source license
Model-weight license
Dataset implications
Commercial use
Redistribution
Attribution
Copyleft dependencies
Voice restrictions
```

A source-code license does not automatically mean the model weights or bundled voices have the same terms.

Current research notes:

- Kokoro model variants are published under permissive terms, but Android wrappers and phonemizer dependencies can introduce separate obligations.
- Pocket TTS source code and model artifacts must be evaluated separately.
- AI4Bharat Indic-TTS offers Indian-language research/models and should be evaluated for quality, runtime practicality and exact redistribution obligations.
- Avoid making the app dependent on non-commercial weights.

Before Play Store release, perform a final dependency/license audit.

---

# 41. Hindi / Indic Engine Track

Treat Hindi as a parallel development track rather than an afterthought.

Tasks:

1. collect evaluation sentences;
2. test native-script Hindi;
3. test names;
4. test numeric/currency normalization;
5. test Hinglish;
6. compare local Indic TTS engines;
7. evaluate if a separate Hindi engine is better than one multilingual model;
8. hide engine switching from the listener.

The ideal user experience:

```text
English paragraph
↓
Hindi dialogue
↓
English phrase
```

with one coherent narrator identity.

This may require more engineering than the initial English MVP.

---

# 42. Book Memory

Book Memory should be structured, not a free-form LLM chat history.

Store:

- characters;
- aliases;
- pronunciation;
- narrator settings;
- speaker assignment;
- chapter metadata;
- user corrections.

Do not store unnecessary "AI thoughts."

Book Memory should remain tiny even for huge novels.

---

# 43. Incremental Character Discovery

Do not analyze a 5,000-page novel fully on import.

Process characters as needed.

Flow:

```text
new name detected
↓
candidate character created
↓
repeated evidence
↓
confidence increases
↓
voice assignment remains stable
```

If uncertain, use narrator voice.

User corrections override automation.

---

# 44. Synthesis Priority Queue

Priority classes:

## P0
Audio needed within the next minute.

## P1
Current chapter ahead.

## P2
Next chapter.

## P3
Speculative overnight/charging generation.

Always execute P0 first.

This prevents background preprocessing from delaying playback.

---

# 45. Pre-generation While Charging

Optional preference:

> Prepare books while charging

When:

- device charging;
- battery sufficiently high;
- acceptable thermal state;
- storage available;

generate ahead.

Possible additional option:

> Prepare entire book while charging.

This is how a user can gradually build a full offline audiobook at no server cost.

---

# 46. Storage Estimation

Before "Generate entire audiobook":

Show approximate:

```text
Estimated audio duration: 128 hours
Estimated storage: ~2.1–3.1 GB
```

Calculation must use selected codec/bitrate and estimated word count.

Let user select:

- Compact;
- Standard;
- High.

Live streaming cache should remain much smaller.

---

# 47. App Performance Budgets

These are targets, not promises.

## UI

- startup to library: < 1 second on reference device after warm launch;
- scrolling remains smooth during background synthesis;
- never run inference on main thread.

## Initial listening

Goal:

- first playable audio within a few seconds after book structure is available.

## Buffer

Maintain enough generated audio that normal playback never catches the synthesis queue on supported devices.

## Memory

Inference model can be large, so the rest of the app should remain conservative.

Track separately:

- Java/Kotlin heap;
- native heap;
- model mappings;
- PCM buffers;
- cache.

---

# 48. Minimum Supported Device Strategy

Do not promise full neural narration on every Android phone.

Create capability tiers after benchmarks.

Example concept:

### Tier A
High-performance phones: full local narration.

### Tier B
Midrange: natural narration with conservative pre-generation.

### Tier C
Low memory/old devices: smaller model or Android TTS fallback.

The application should test device capability and recommend a voice pack.

---

# 49. Benchmark Device Matrix

At minimum test:

- one low/midrange Android;
- one mainstream midrange Android;
- one recent flagship;
- one Samsung device;
- one Pixel/device close to stock Android.

Measure:

- synthesis speed;
- startup;
- thermal behavior;
- battery drain;
- memory;
- 1-hour playback stability.

Later add devices based on crash analytics.

---

# 50. Quality Evaluation

Do not judge TTS only from 10-second samples.

Listening tests:

## 30-second test
Basic naturalness.

## 5-minute test
Rhythm/repetition.

## 30-minute test
Audiobook fatigue.

## 2-hour test
Thermals, buffering and consistency.

## Multi-chapter test
Voice drift and character consistency.

Evaluation questions:

- Does the voice become annoying?
- Are pauses predictable/mechanical?
- Are names consistent?
- Does dialogue remain understandable?
- Does the voice reset between sentences?
- Are chapter transitions pleasant?
- Is 1× playback sustainable for long listening?

---

# 51. Test Corpus

Create a private/legal test corpus containing original or public-domain text.

Include:

- modern prose;
- classic fiction;
- dialogue-heavy fiction;
- technical writing;
- Hindi;
- Hinglish;
- numbers;
- dates;
- unusual punctuation;
- Indian place names;
- Indian personal names;
- abbreviations;
- tables/lists.

Never use copyrighted book content as bundled test data without permission.

---

# 52. MVP Definition

The first usable MVP should do only:

- Android app;
- import TXT/EPUB;
- extract chapters;
- one strong offline English neural voice;
- play/pause/seek;
- reading progress;
- background playback;
- sentence-level chunking;
- simple text normalization;
- generated-audio queue;
- rolling cache;
- resume after app restart.

No accounts.

No server.

No OCR.

No character voices.

No advanced AI.

The MVP's purpose is to prove:

> Can we listen to a full novel naturally, offline, without interruptions?

---

# 53. MVP Success Gate

Do not proceed to advanced features until:

1. one complete public-domain novel can be imported;
2. it can play for 2 hours continuously;
3. generation stays ahead of playback;
4. app survives screen-off/background use;
5. resume is exact enough;
6. no major memory leak;
7. model license is understood;
8. users consider the voice comfortable enough for long listening.

---

# 54. Development Phases

## Phase 0 — Research Prototype

**Goal:** choose the first local TTS engine.

Build a tiny benchmark app.

Tasks:

- integrate candidate engines;
- run shared corpus;
- collect speed/quality/thermal numbers;
- perform license review;
- select engine V1.

Deliverable:

```text
tts-benchmark/
benchmark-results.md
license-matrix.md
```

## Phase 1 — Reading Core

Build:

- app shell;
- library;
- TXT import;
- EPUB import;
- chapter model;
- Room database;
- player;
- background service;
- position persistence.

Initially allow Android system TTS if needed while infrastructure is built.

Deliverable: a functioning book reader.

## Phase 2 — Offline Neural TTS

Integrate selected model.

Build:

- model manager;
- download/validation;
- `TtsEngine` abstraction;
- synthesis queue;
- segment cache;
- playback bridge.

Deliverable: one novel can be read entirely offline using local neural speech.

## Phase 3 — Long-Book Engine

Build:

- rolling synthesis buffer;
- adaptive queue;
- crash recovery;
- storage limits;
- thermal throttling;
- charging pre-generation.

Stress test with a synthetic equivalent of 5,000 pages.

Deliverable: book length becomes practically irrelevant to RAM/playback stability.

## Phase 4 — Narration Director V1

Build:

- sentence segmentation;
- paragraph pacing;
- punctuation rules;
- heading/chapter treatment;
- dialogue extraction;
- basic dialogue attribution;
- configurable modes.

Deliverable: speech begins to feel like narration instead of raw TTS.

## Phase 5 — Speech Normalization

Implement:

- dates;
- times;
- money;
- percentages;
- numbers;
- units;
- abbreviations;
- pronunciation dictionary.

Deliverable: common written constructs sound natural.

## Phase 6 — Character Memory

Implement:

- character registry;
- aliases;
- speaker persistence;
- subtle character variants;
- user editor.

Deliverable: fiction maintains consistent character treatment across chapters.

## Phase 7 — Hindi

Implement:

- Hindi voice engine evaluation;
- Devanagari normalization;
- Indian numeric conventions;
- names;
- Hindi reading profile.

Deliverable: high-quality native Hindi offline reading.

## Phase 8 — Hinglish

Implement:

- span language detection;
- Roman Hindi recognition;
- code-switch handling;
- cross-engine/voice continuity;
- custom pronunciation.

Deliverable: natural mixed Hindi-English reading.

## Phase 9 — More Inputs

Add:

- PDF;
- DOCX;
- share webpage/text;
- clipboard.

Then:

- scanned PDF;
- photo OCR.

## Phase 10 — Audiobook Export

Add optional:

- chapter generation;
- full-book generation;
- Opus/AAC/MP3/M4B pipeline;
- metadata;
- cover;
- chapter markers.

## Phase 11 — Polish

- accessibility;
- tablet layouts;
- widgets;
- Android Auto if appropriate;
- onboarding;
- diagnostics;
- crash handling;
- final Play Store compliance.

---

# 55. Suggested Development Order

Do not start with beautiful UI.

Order:

```text
1. TTS benchmark
2. Book parser
3. Segment generator
4. Cache
5. Player
6. Background playback
7. 5,000-page stress test
8. Narration rules
9. Pronunciation
10. Hindi/Hinglish
11. UI polish
12. OCR/extras
```

This protects us from spending weeks polishing an app before knowing whether sustained local narration works well enough.

---

# 56. 5,000-Page Stress Test

Create a generated test document around:

- 1–1.5 million words;
- thousands of paragraphs;
- hundreds of chapters;
- dialogue;
- repeated characters;
- punctuation edge cases.

Test:

- import time;
- database size;
- scrolling;
- chapter search;
- queue size;
- RAM;
- background generation;
- restart recovery;
- cache cleanup.

The app must not load the entire book into memory at once.

---

# 57. Performance Instrumentation

Add an internal diagnostics page.

Track:

```text
Engine
Model version
Average real-time factor
P50/P95 segment generation time
Buffered audio duration
Native memory
Heap memory
Thermal status
Cache size
Failed segments
Retry count
Battery state
```

Keep this developer-oriented and optionally exportable as a log.

This will be invaluable while optimizing different phones.

---

# 58. Offline-Only Test

Before release, perform a full airplane-mode test:

1. install app;
2. download model;
3. disable internet;
4. import a local novel;
5. listen;
6. close app;
7. reboot phone;
8. continue listening;
9. change chapters;
10. change speed;
11. edit a pronunciation;
12. listen for several hours.

Everything above should work.

---

# 59. Accessibility

From the beginning:

- screen reader labels;
- large touch targets;
- scalable text;
- high contrast;
- no control indicated by color alone;
- hardware media buttons;
- simple "listen now" flow.

A TTS app should be especially strong on accessibility.

---

# 60. Monetization Philosophy

Core local reading can remain free/unlimited.

Possible optional revenue later:

- premium curated voice packs where licensing permits;
- advanced local Narrator packs;
- cloud studio narration;
- creator export tools;
- cross-device sync;
- donation/supporter option.

Do not build core architecture around monetization.

Do not make server expenditure proportional to ordinary listening.

---

# 61. What We Should NOT Build Initially

Avoid:

- social network;
- user accounts;
- cloud book storage;
- complex sync;
- AI chatbot;
- voice marketplace;
- voice cloning;
- dozens of voices;
- full document editor;
- cloud backend;
- subscription system;
- giant local LLM.

These distract from proving the core product.

---

# 62. Primary Technical Risks

## Risk 1 — Local voice quality is good in demos but tiring after 30 minutes

Mitigation: long-form listening tests before choosing the model.

## Risk 2 — Phone cannot synthesize faster than playback

Mitigation: buffer ahead, charging pre-generation, smaller model tier, device capability detection.

## Risk 3 — Thermal throttling

Mitigation: adaptive worker count, thermal monitoring, larger buffer, suspend speculative generation.

## Risk 4 — Hinglish pronunciation

Mitigation: separate language-routing layer and pronunciation memory.

## Risk 5 — Long-form voice reset

Mitigation: Narration Director, semantic chunking, post-processing and context metadata.

## Risk 6 — Model/dependency licensing

Mitigation: engine abstraction and mandatory release license audit.

## Risk 7 — PDF quality

Mitigation: treat EPUB/TXT as first-class; add OCR/scanned PDF separately.

---

# 63. Definition of "Speechify-Like Quality"

We should not define success as:

> sounds identical to Speechify in a 10-second demo.

Define it as:

> A user can comfortably listen to a novel for hours without being distracted by robotic rhythm, bad pauses, repeated pronunciation errors or synthesis interruptions.

Metrics:

- natural voice;
- correct reading;
- reliable streaming;
- long-form consistency;
- good names;
- sensible dialogue;
- no credit anxiety;
- offline operation.

That is a stronger product objective.

---

# 64. First Release Feature Set

Recommended first public beta:

### Reading
- EPUB
- TXT
- PDF text extraction
- chapter navigation

### Audio
- one or two excellent offline English voices
- Natural mode
- speed control
- sentence skip
- sleep timer

### Intelligence
- normalization
- basic dialogue handling
- pronunciation corrections

### Reliability
- rolling generation buffer
- crash recovery
- background playback
- thermal/battery aware scheduling

### Privacy
- no account
- no upload
- no listening credits

Hindi can either be beta in this release or follow immediately after the English engine is stable.

---

# 65. Second Release

Add:

- Hindi;
- Hinglish beta;
- character memory;
- Calm/Dramatic modes;
- DOCX;
- share-to-app;
- better pronunciation UI;
- model/voice downloads.

---

# 66. Third Release

Add:

- OCR;
- scanned PDFs;
- photo reading;
- full audiobook export;
- advanced local narration model where device supports it;
- optional premium/cloud features.

---

# 67. Repository Plan

Suggested repository:

```text
offline-narrator/
    android/
    docs/
        architecture.md
        narration-director.md
        tts-engine-contract.md
        storage.md
        licensing.md
        benchmark-plan.md
        testing.md
    benchmark/
    scripts/
    test-corpus/
    LICENSES/
```

Never bury architecture knowledge inside code only.

---

# 68. Git Strategy

Use:

```text
main
develop
feature/*
```

For a small team/solo build, keep process lightweight.

Require:

- build passes;
- unit tests for parser/normalizer;
- benchmark regression check before model changes;
- changelog for model versions.

Tag stable milestones.

---

# 69. Testing Strategy

## Unit tests

- normalization;
- sentence splitting;
- chapter parsing;
- cache hashing;
- queue priority;
- pronunciation resolution.

## Integration tests

- import → segment → synthesize → play;
- process death → resume;
- model update;
- cache eviction.

## Device tests

- screen off;
- Bluetooth;
- calls/audio focus;
- low battery;
- thermal throttling;
- storage full;
- app backgrounded;
- device reboot.

---

# 70. Narration Regression Suite

Keep a fixed set of lines.

Every engine/director change renders them.

Compare:

- waveform duration;
- missing words;
- long pauses;
- pronunciation;
- perceived quality.

Store human notes.

This prevents "improvements" from quietly making another type of content worse.

---

# 71. Quality Feedback Inside Beta

Optional local feedback:

```text
How did this sentence sound?
👍  👎
```

If thumbs down:

- pronunciation;
- pause;
- voice;
- wrong language;
- skipped/misread text.

Initially keep feedback local or let user explicitly export diagnostics.

Do not upload book content by default.

---

# 72. Decision Log

Maintain `/docs/decisions/`.

For major decisions record:

```text
Problem
Options
Decision
Why
Risks
Revisit condition
```

Examples:

- chosen TTS engine;
- codec;
- PDF parser;
- Hindi engine;
- cache policy.

This prevents architecture from becoming inconsistent over months of development.

---

# 73. Immediate Next Step

Do **not** start by building the full application.

The first implementation should be:

## `Offline Narrator TTS Benchmark`

A tiny Android app with:

- text box;
- fixed benchmark passages;
- model selector;
- voice selector;
- Generate/Play;
- synthesis timer;
- real-time factor;
- RAM;
- device temperature;
- audio save;
- long-run test.

Candidate engines should be tested on the same phone.

At the end we choose:

```text
PRIMARY_ENGINE
FALLBACK_ENGINE
ENGLISH_MODEL
INITIAL_VOICE
MODEL_DOWNLOAD_SIZE
MINIMUM_DEVICE_PROFILE
```

Only then begin the real reader.

---

# 74. Phase 0 Exit Report

Before building the app shell, produce:

```text
benchmark-results.md
```

containing:

| Engine | Voice quality | Long-form | RTF | RAM | Model size | Android difficulty | License confidence |
|---|---|---|---:|---:|---:|---|---|
| Kokoro | TBD | TBD | TBD | TBD | TBD | TBD | TBD |
| Pocket | TBD | TBD | TBD | TBD | TBD | TBD | TBD |
| Kitten | TBD | TBD | TBD | TBD | TBD | TBD | TBD |

No winner should be selected from marketing/demo samples alone.

---

# 75. North-Star Test

The most important product test:

> Put the phone into airplane mode, import a very large novel, press play, put the phone in your pocket and listen for two hours.

If the user forgets that the voice is being generated locally, the product is succeeding.

---

# 76. Final Architecture Summary

```text
                  ┌─────────────────┐
                  │ EPUB/PDF/TXT/...│
                  └────────┬────────┘
                           ↓
                 ┌──────────────────┐
                 │ Document Parser  │
                 └────────┬─────────┘
                          ↓
                ┌────────────────────┐
                │ Canonical Book DB  │
                └─────────┬──────────┘
                          ↓
               ┌──────────────────────┐
               │ Text Normalization   │
               └──────────┬───────────┘
                          ↓
               ┌──────────────────────┐
               │ Narration Director   │
               │ dialogue / emotion   │
               │ pacing / characters  │
               └──────────┬───────────┘
                          ↓
               ┌──────────────────────┐
               │ Pronunciation &      │
               │ Language Router      │
               └──────────┬───────────┘
                          ↓
               ┌──────────────────────┐
               │ Semantic Segmenter   │
               └──────────┬───────────┘
                          ↓
              ┌────────────────────────┐
              │ Replaceable TTS Engine │
              │ Kokoro/Pocket/etc.     │
              └───────────┬────────────┘
                          ↓
               ┌──────────────────────┐
               │ Audio Finisher       │
               └──────────┬───────────┘
                          ↓
               ┌──────────────────────┐
               │ Rolling Audio Cache  │
               └──────────┬───────────┘
                          ↓
               ┌──────────────────────┐
               │ Media3 Player        │
               └──────────────────────┘
```

Supporting systems:

```text
Book Memory
Pronunciation DB
Character Registry
Synthesis Scheduler
Thermal Manager
Model Manager
Diagnostics
```

---

# 77. Build Philosophy

The app should not win because it has the most voices.

It should win because it combines:

- excellent local speech;
- long-form reliability;
- audiobook-style direction;
- Indian-language intelligence;
- privacy;
- unlimited listening;
- no mandatory subscription;
- no server dependency.

The model is replaceable.

The **Narration Engine and long-book architecture are the product**.

---

# 78. Current Technology Notes (September 2026)

These are starting points for benchmarking, not permanent architectural commitments.

### Kokoro
Kokoro-82M is an 82M-parameter TTS family with permissively released model variants and an ONNX ecosystem. Android community implementations already demonstrate on-device inference. Some implementations use `espeak-ng`; its licensing and the way it is packaged must be reviewed before commercial distribution.

### Pocket TTS
Kyutai's Pocket TTS is a compact local/CPU-oriented TTS project. Its source and model/voice terms should be reviewed independently before distribution. Android ports exist, but the production app should not depend blindly on unofficial wrappers.

### AI4Bharat Indic-TTS
AI4Bharat provides open Indian-language TTS work covering languages including Hindi and Rajasthani. It is relevant to our Indic-language track, but mobile runtime quality, model footprint and exact redistribution requirements must be benchmarked/audited.

### Important
Do not commit the application architecture to a model merely because it currently sounds best. Open TTS models are improving quickly.

---

# 79. Reference Starting Points

- Kokoro Android example: https://github.com/ffmpegkit-maintained/kokoro-android
- Kokoro model ecosystem: https://github.com/zboyles/Kokoro-82M
- Pocket TTS license/source: https://github.com/kyutai-labs/pocket-tts
- Pocket TTS Android experimentation: https://github.com/The-unknown-Shadowman/PocketTTS-Android-Engine
- AI4Bharat Indic-TTS: https://github.com/AI4Bharat/Indic-TTS

These are research references. They should not be copied wholesale into production without source, dependency, model and voice-license review.

---

# 80. First Build Checklist

When development starts, do this in order:

- [ ] Create Android benchmark project.
- [ ] Add fixed English benchmark corpus.
- [ ] Add fiction/dialogue benchmark corpus.
- [ ] Add Hindi benchmark corpus.
- [ ] Add Hinglish benchmark corpus.
- [ ] Integrate first candidate TTS engine.
- [ ] Record synthesis speed and RAM.
- [ ] Test 30-minute generation.
- [ ] Integrate second candidate.
- [ ] Integrate third candidate if practical.
- [ ] Complete license matrix.
- [ ] Choose provisional V1 engine.
- [ ] Define `TtsEngine` interface.
- [ ] Build EPUB/TXT parser.
- [ ] Build Room book schema.
- [ ] Build speech segment queue.
- [ ] Build Media3 playback service.
- [ ] Add rolling cache.
- [ ] Run 5,000-page synthetic stress test.
- [ ] Add Narration Director V1.
- [ ] Add normalization engine.
- [ ] Add pronunciation overrides.
- [ ] Add adaptive thermal/battery scheduler.
- [ ] Run airplane-mode 2-hour test.
- [ ] Only then begin major UI polish.

---

# 81. The First Concrete Milestone

**Milestone: "One Book, Airplane Mode"**

Definition:

1. Install app.
2. Download the selected model.
3. Turn on airplane mode.
4. Import a full EPUB novel.
5. Press Play.
6. Listen continuously for at least two hours.
7. Lock the phone.
8. Use Bluetooth/headset controls.
9. Reopen the app.
10. Resume from the correct place.
11. Finish a chapter without cloud access or credits.

Once this milestone is solid, we have the foundation of the real product.

Everything else—Hindi, Hinglish, character narration, OCR, export, additional voices—is an enhancement on top of a proven core.

---

## End of Blueprint
