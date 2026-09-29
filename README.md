# Bolo

Current checkpoint: **v0.20 Multi-format Import Hub**

Supported imports:
- EPUB
- text-based PDF
- DOCX
- TXT
- HTML / HTM

All imports feed the same offline Kokoro reader and large-document navigation
index. Text-based PDFs keep their real page numbers; reflowable formats use
estimated pages with exact underlying word/section positions.

Scanned PDFs are detected and reported as requiring OCR.

Build artifact: `bolo-v0.20-import-hub`

See `BOLO_V0.20_CHECKPOINT.md`.
