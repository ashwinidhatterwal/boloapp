# Bolo v0.24 — Novel Reader

This checkpoint deepens Natural Narrator and replaces the large card-based reader with a compact text-first audiobook UI.

## Narration engine v3

- **Real Kokoro token budgeting:** Bolo now exposes the exact punctuation-preserving Kokoro token counter and plans model calls to a healthy ~380-token target with a 440-token ceiling, rather than guessing from character count.
- **Paragraph/thought context:** compatible sentences inside the same paragraph can share one inference call; paragraph, scene and chapter boundaries are never crossed.
- **Conservative dialogue continuity:** after two characters have both been explicitly identified nearby, Bolo can infer a short alternating unattributed turn. Long narration and scene/chapter boundaries clear the memory. One known speaker is never enough to invent the other.
- **Subtle delivery state:** hesitation is slightly slower, clearly action-heavy narration may be ~2% faster, reflective narration may be ~1.5% slower, and dialogue stays neutral. Listener playback speed remains independent.
- **Lighter character blending:** character colour is reduced from about 14% to about 8% so the performance remains recognizably one narrator.
- **Two-sided adaptive silence:** the WAV finisher estimates a per-file noise floor, trims excessive leading silence, and only adds/trims the trailing boundary when necessary. It never intentionally cuts audible speech.
- **Micro edge fades:** tiny fades are applied around the first/last audible PCM frames after editing to reduce joins/clicks without creating an audible effect.
- **Acoustic sentence anchors:** when one Kokoro batch contains multiple sentences, Bolo searches for low-energy valleys near expected boundaries. Media3 stores those time/word anchors so highlighting and resume interpolation track the spoken sentence more closely.
- **Cache generation:** bumped to `kokoro-reader-v4-context-reader`; older rendered batches are not reused.

## Professional reader/player redesign

The reader is now text-first instead of card-first.

- Book title/author remain in a compact top bar.
- Chapter name and page context sit above the text and open the chapter selector when tapped.
- The chapter text consumes the main screen. Sentences use comfortable 18sp / 29sp reading typography.
- Current sentence gets a restrained highlight; ordinary lines are not rendered as chunky buttons.
- Any sentence remains tappable to start narration from its exact indexed word position.
- Playback is a compact fixed bottom bar with a slim scrubber, previous chapter, 50dp play/pause, next chapter and a small overflow button.
- Speed, narrator, stop, page jumps, cache controls and diagnostics live in the options bottom sheet instead of occupying the main reader.
- Chapter search/selection and huge-book navigation remain intact.

## Validation in this source package

Pure Kotlin checks cover dialogue-turn inference, token-budgeted batching, source-word accounting, subtle prosody and adaptive WAV boundary handling. The Android/Compose build must still be confirmed by GitHub Actions after push.


## v0.24.1 — dialogue boundary fix
GitHub run #30 exposed a real scene-memory ordering bug: the final unattributed dialogue line in a chapter was reset before alternating-speaker inference. Conversation state is now resolved for the current unit first, then cleared after a scene/chapter boundary.
