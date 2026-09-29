# Bolo architecture — v0.19

## Flow

EPUB
→ `EpubBookStore`
→ chapter files + cumulative word index
→ `BookReaderRuntime`
→ `BookNarrationSegmenter`
→ Kokoro `TtsEngine`
→ `NarrationCache`
→ `BackgroundAudioController`
→ Media3 `MediaSessionService`

## Why locations instead of fixed pages

EPUB text reflows with font size and screen size, so a stable printed page
number generally does not exist. Bolo indexes exact word locations and exposes
a user-friendly estimated page at 250 words/page.

The index means whole-book navigation is O(log chapters) for chapter lookup. Each chapter also stores a word→character checkpoint about every 500 words, so jumping deep inside one giant XHTML chapter only scans a small local tail instead of the chapter from the beginning.

## Lifetime

`BookReaderRuntime` is an application-process singleton with its own coroutine
scope. The UI ViewModel is intentionally thin.

Playback is owned by `BoloPlaybackService`, not the Activity. This avoids losing
the player when the screen turns off or the UI leaves the foreground. The
runtime can continue rolling Kokoro generation while the foreground playback
service keeps the process alive.

## Storage

`files/books/<book id>/book.json`
`files/books/<book id>/chapters/00000.txt`
`files/narration-cache/<sha>.wav`

The imported EPUB is used only during indexing and is not duplicated afterward.

## Resume

The current media item carries chapter/word metadata in `MediaMetadata.extras`.
The runtime persists the current segment start and audio millisecond position,
so resume can reuse the same cached segment and seek within it.
