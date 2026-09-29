# Bolo v0.20 — Multi-format Import Hub

Bolo now imports these document types through one file picker and converts all
of them into the same indexed local book representation:

- EPUB
- PDF (text-based)
- DOCX
- TXT
- HTML / HTM

## Shared reader architecture

All formats are normalized into:

`document -> sections -> word checkpoints -> location index -> Bolo reader`

The player, Kokoro narration, cache, resume, large-book scrubber and thermal
logic do not depend on the original file format.

## Large document navigation

- EPUB/DOCX/TXT/HTML use exact section/word locations and an estimated page scale
  of about 250 words per page.
- PDF preserves the source PDF's real page count and a page-to-global-word index.
- PDF scrubbing and Go to page use the actual PDF page anchors.
- Sections are stored as separate text files and carry internal word checkpoints.
- Very large unstructured TXT/DOCX/HTML content is automatically split into
  manageable ~7,500-word sections instead of becoming one giant UI string.
- PDF text is extracted page-by-page and grouped into 20-page storage sections,
  while every original PDF page keeps its own navigation anchor.

Jumping hundreds or thousands of pages never requires synthesizing or reading
all skipped text. A jump resolves directly to the indexed location and starts a
new rolling narration queue there.

## PDF behavior

PDF text extraction uses PdfBox-Android 2.0.27.0. If the PDF contains too little
extractable text, Bolo stops the import with a clear scanned-PDF/OCR-required
message. OCR is intentionally not bundled into this checkpoint.

## DOCX behavior

DOCX is parsed directly from its Open XML package without Apache POI. The parser
streams `word/document.xml`, recognizes Word heading styles where available,
and uses those headings as Bolo sections. This keeps the import layer lighter.

## Compatibility

Books imported by v0.19/v0.19.1 remain readable. Older metadata without a
format field is treated as EPUB unless the saved source filename indicates
another format.
