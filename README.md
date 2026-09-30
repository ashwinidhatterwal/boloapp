# Bolo

Current checkpoint: **v0.25 Audiobook Compiler**

Bolo is now a prepare-ahead offline audiobook compiler/player built around Kokoro.

Current architecture:
- EPUB/PDF/DOCX/TXT/HTML import and huge-book indexing
- chapter-first narration planning before synthesis
- real Kokoro-token chunking targeted at the model's 100–200-token quality range
- punctuation-preserving Kokoro G2P
- scene/dialogue context with confidence-gated, restrained performance hints
- one consistent narrator voice by default
- automatic acoustic QC with one conservative retry for suspicious output
- structural pause mastering and sentence anchors
- current chapter is fully compiled before playback starts
- Kokoro is idle while prepared chapter audio is playing
- compact text-first Media3 reader/player

GitHub artifact: `bolo-v0.25-audiobook-compiler`

See `BOLO_V0.25_CHECKPOINT.md` and `NARRATION_COMPILER_DESIGN.md`.
