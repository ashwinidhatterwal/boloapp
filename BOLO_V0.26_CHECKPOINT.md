# Bolo v0.26 — Audiobook Studio

## GitHub failure fixed

Failed Actions run: https://github.com/ashwinidhatterwal/boloapp/actions/runs/36695946325

The pinned Kokoro AAR built successfully. App compilation failed because
`BookReaderRuntime.kt:976` referenced an undefined `formatPreparedForStatus`.
The obsolete streaming preparation code has now been replaced with the durable
chapter preparation pipeline; that undefined call no longer exists.

## Prepared audio

- Each source/voice/model/director configuration has a persistent chapter manifest.
- Plan the full chapter from word zero, so seeking does not reset scene context,
  change chunk boundaries or regenerate a different chapter tail.
- Save a checkpoint after every completed chunk. Reopening interrupted work skips
  already verified chunks. WorkManager recovers scheduled jobs after process death.
- Commit audio through a staging file, flush it to disk, then atomically update
  the manifest. Validate checksums before reusing completed audio; cache successful
  verification while file attributes remain unchanged to avoid repeated disk scans.
- Prefer 64 kbps AAC-LC M4A (about 29 MB/hour), retaining WAV as a device codec fallback.
- Prepared audio is not silently evicted. Low storage stops preparation and keeps
  completed work. Clear prepared audio requires confirmation.
- Seek within the current prepared playlist immediately; cross-chapter navigation
  reloads that chapter's saved timeline rather than resynthesizing a changed tail.
- Automatically play the next chapter when its selected edition is already complete.
  An unprepared chapter waits for explicit preparation, keeping synthesis out of listening.

## Preparation and power

Reader options offer this chapter, the next three chapters, or the remaining book.
Charging-only is the default scheduling option. Background work also requires battery
and storage headroom. Four-chunk work slices checkpoint between runs, obey thermal
limits, and can be cancelled from the notification or reader options.

The Kokoro singleton is serialized across foreground and background compilation.
A playback session reserves the inference gate, including when paused, so normal
notification/headset resumes do not race background inference. Stop/end releases
that reservation. Scheduled work resumes when Android's scheduler permits it.

Kokoro loads only during preparation and releases its native session before
prepared playback. The default preparation profile uses up to four threads, full
graph optimization and disabled thread spinning. No measured battery saving is
claimed without a physical-device benchmark.

## Deeper narration

The optional semantic director accepts an HTTPS OpenAI-compatible chat-completions
endpoint, model and API key. Enabling it explicitly permits sending book text to
that configured provider; provider costs may apply. Keys are encrypted with
Android Keystore. Backups are disabled to avoid exporting provider credentials.

It performs a chapter overview, then exact text windows with neighbouring lines
and rolling scene/character memory. The prompt covers point of view, motivation,
listener, communicative intent, irony, negation, reveals, narrator stance versus
character stance, and delivery manner versus emotion.

Responses may annotate existing unit indices only. No returned text is used as
book content. Missing/duplicate indices fail validation. Strong labels need literal
local evidence. Invented names are rejected. Low-confidence or ungrounded hints
become neutral. Network/provider/schema failure falls back to the offline plan
with an explicit warning; it does not silently claim semantic analysis succeeded.
Chapter semantic analysis has a three-minute cap.

Actual controls remain restrained: bounded tempo, level and structural pause
adjustments. Kokoro does not become a fully emotion-conditioned voice actor.
Offline mode uses improved attribution-aware rules, including negation handling.
A manual pronunciation dictionary supports `name=spoken form` lines without
changing the source text or reading positions. Changing settings creates another
edition; old audio remains until explicitly cleared.

## Reliability fixes

- Correct curly single-quote contractions and common plural possessives.
- Map source positions to original character spans and validate against original
  chapter word counts, not a potentially incorrect parsed-unit total.
- Preserve source width through long-token fragmentation.
- Bundle very short neutral dialogue paragraphs where useful, without crossing
  scene boundaries or blending incompatible confident performance cues.
- Reject failed acoustic QC after the neutral retry. Invalid/truncated PCM is rejected.
- Exclude deliberately mastered trailing silence from duration-per-word checks.
- Apply restrained level mastering, then inspect output again before committing it.
- Recover a retryable Play state after compilation failure; completed chunks remain.
- Surface player errors. Preserve microphone-free/background Media3 playback.
- Freeze the selected voice throughout a compilation pass to prevent mixed-voice chunks.

## Validation and limits

See VALIDATION.md for the exact build and test results. Runtime acoustic QC detects
large waveform problems; it does not recognize words or prove exact pronunciation,
no omissions/repetitions, or correct emotional interpretation. Silence-based word
anchors remain approximate. Semantic tests exercise response validation with a fake
provider, not a live paid model. Physical Android listening, AAC boundaries, thermal
behavior, scheduler interruption and real provider output still require device QA.

The source archive includes .github and a checksum-pinned Gradle 9.6 wrapper. The
GitHub workflow builds the actual pinned native Kokoro AAR and uploads test/lint
reports, including when verification fails. No API keys or model weights are bundled.
