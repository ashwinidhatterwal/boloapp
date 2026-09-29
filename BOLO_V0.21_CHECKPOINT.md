# Bolo v0.21 — Large-Book Import + Narration Director V1

This checkpoint batches two related milestones.

## Large-book import overhaul

The v0.20 importer could repeatedly scan the same EPUB chapter while extracting,
counting words, splitting sections and building navigation checkpoints. On
multi-million-word books this could turn indexing into a multi-minute job.

v0.21 changes the large EPUB path:

- EPUB XHTML is parsed as a stream with Android XmlPullParser when valid.
- Malformed XHTML keeps a compatibility HTML fallback per affected chapter.
- EPUB sections use a one-pass word scan that writes ~7,500-word chunks and
  creates 500-word navigation checkpoints during that same scan.
- Regex MatchResult allocation is removed from the global word counter and
  `dropWords()` navigation helper.
- File copy progress is reported for large source documents.
- EPUB progress shows spine item N / total and percentage.
- PDF progress reports page N / total.
- DOCX reports paragraph progress.
- import can be cancelled.
- `.importing` staging is deleted on cancellation/failure.
- finished books still appear atomically only after metadata is saved.

The sparse navigation model remains unchanged: a jump hundreds or thousands of
pages away resolves to a book/chapter/checkpoint location without synthesizing
or traversing the intervening content.

## Narration Director V1

The first deterministic narration layer is now active:

- quoted speech is separated from narration;
- questions and exclamations are retained as delivery cues;
- explicit nearby speaker attribution is detected conservatively:
  - `"Wait," Arjun said.`
  - `"Wait," said Arjun.`
  - `Arjun said, "Wait."`
- pronouns such as `he said` / `she said` are deliberately not treated as named
  characters;
- if a speaker is not confidently identified, the selected narrator voice is
  used rather than guessing;
- confidently identified characters receive a stable per-book Kokoro voice;
- assignments persist across sessions;
- deleting a book deletes its saved character mappings.

Available character voices come from Bolo's existing curated Kokoro pack:
Onyx, Michael, George, Fenrir and Heart. The user's selected narrator remains
the primary narrator voice.

## UI

During import the Library shows:

- current phase;
- current chapter/page/paragraph detail;
- percentage where total work is known;
- current / total;
- Cancel import.

Reader details now also show:

- dialogue segments;
- characters voiced.

No cloud model or account is required.
