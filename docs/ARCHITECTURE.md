# Bolo architecture — v0.20

## Import pipeline

EPUB / PDF / DOCX / TXT / HTML
→ `DocumentBookStore`
→ normalized section text files + location metadata
→ `BookReaderRuntime`
→ `NarrationDirector`
→ Kokoro `TtsEngine`
→ `NarrationCache`
→ `BackgroundAudioController`
→ Media3 `MediaSessionService`

The reader never depends on the original document format after import.

## Location model

Every stored section has:
- cumulative global word start;
- exact word count;
- a word→character checkpoint roughly every 500 words.

This lets Bolo jump into a distant section or deep inside one unusually large
section without walking all preceding text.

Reflowable formats (EPUB, DOCX, TXT, HTML) expose a user-friendly estimated page
scale at about 250 words/page. The underlying section/word locations are exact.

PDF keeps an additional `PageAnchor` list with the original PDF page number and
its global word start. The reader scrubber and Go to page use those real page
anchors instead of estimated pages.

## Import memory strategy

- EPUB is read spine item by spine item from the ZIP package.
- DOCX streams `word/document.xml` with Android's pull parser; it is not loaded
  as one huge DOM.
- TXT/HTML are normalized then automatically split into manageable sections.
- PDF is extracted one page at a time. PdfBox-Android is opened with
  `MemoryUsageSetting.setupTempFileOnly()` so large PDF stream buffers prefer
  scratch storage instead of unrestricted Java heap.

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

The selected source file is used only during indexing and is not duplicated
afterward.

## Resume

The current media item carries section/word metadata in `MediaMetadata.extras`.
The runtime persists the current segment start and audio millisecond position,
so resume can reuse the same cached segment and seek within it.
