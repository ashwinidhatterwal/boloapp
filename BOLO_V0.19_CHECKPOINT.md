# Bolo v0.19 — One Book, Airplane Mode

This checkpoint turns Bolo from a pasted-text prototype into an indexed EPUB
audiobook reader.

## Huge-book navigation

Bolo does not load a whole EPUB into the reader screen and does not synthesize
pages that are skipped.

At import time it creates:
- one lightweight metadata record for the book;
- a spine-ordered chapter index;
- one plain-text file per chapter;
- exact cumulative word offsets per chapter;
- internal word→character checkpoints every ~500 words inside each chapter.

For reflowable EPUBs there is no universal printed page number. Bolo therefore
uses an explicit estimate of 250 words per page.

This enables:
- a whole-book scrubber;
- direct `Go to page ~N`;
- `-50 pages` / `+50 pages`;
- lazy-loaded table of contents;
- previous / next chapter navigation.

A jump maps:
`estimated page -> global word -> chapter -> local word offset`

Only the destination chapter is opened. Skipped chapters are not synthesized. The internal checkpoints also prevent long scans when a poorly structured EPUB stores hundreds of pages inside one XHTML file.

## EPUB import

Supported in this checkpoint:
- EPUB container.xml
- OPF metadata / manifest / spine
- EPUB3 navigation document titles when available
- EPUB2 NCX titles when available
- XHTML/HTML chapter extraction
- chapter text normalized and stored locally

The original EPUB is not duplicated in app storage after indexing; Bolo keeps
only the audio-reader representation.

## Resume

Progress is saved continuously as:
- book id
- chapter index
- narration segment start word
- exact audio position inside that generated segment
- approximate global word for library progress/page display

On reopening the book Bolo recreates that same segment, normally from the audio
cache, and seeks to the saved millisecond position.

## Background playback

Playback now lives in a Media3 `MediaSessionService`.

This provides:
- screen-off playback;
- Android system media notification;
- standard headset / Bluetooth media controls;
- playback that can continue when the Activity is no longer visible.

The narration scheduler lives in a process-level runtime rather than the
Activity ViewModel. While the playback foreground service keeps the app process
alive, rolling Kokoro generation and cache refill can continue with the screen
off.

## Long-book generation

Bolo reads one chapter file at a time and generates only a rolling reserve.
It does not create audio for the whole book.

Adaptive listening reserve remains:
- 1.0x: 12 s initial / 45 s target
- 1.25x: 30 s initial / 90 s target
- 1.5x: 60 s initial / 180 s target
- 1.75x: 90 s initial / 240 s target
- 2.0x: 120 s initial / 300 s target

Severe Android thermal pressure pauses new generation while already-cached
audio can keep playing.

## Narrators

- Onyx — American male (default)
- Michael — American male
- George — British male
- Fenrir — American male
- Heart — American female

## Acceptance test

1. Import a real EPUB.
2. Enter airplane mode.
3. Start narration.
4. Lock the phone and confirm playback continues.
5. Use notification/headset play-pause.
6. Return to Bolo.
7. Jump hundreds of estimated pages with the scrubber or page dialog.
8. Confirm generation resumes around the destination rather than processing
   skipped chapters.
9. Close/reopen the book and confirm position resume.


## v0.19.1 — Android EPUB XML parser fix

Observed Android error:
`This parser does not support specification "Unknown" version "0.0"`

Root cause:
Android's built-in `DocumentBuilderFactory` throws
`UnsupportedOperationException` when `setXIncludeAware(false)` is called.

Fix:
- removed the XInclude-awareness setter entirely;
- namespace parsing remains enabled;
- external general/parameter entities are disabled when supported;
- external DTD loading is disabled when supported;
- a local empty `EntityResolver` prevents external network/file resolution;
- DOCTYPE declarations are not rejected outright, preserving compatibility
  with common EPUB 2 NCX files.

No large-book indexing, page-location, chapter, player, cache or Kokoro logic
changed.
