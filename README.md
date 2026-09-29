# Bolo — Phase 0 Voice Lab

Current checkpoint: **v0.16 Supertonic clean trial**

The repository builds two APKs:

- `Bolo-v0.16.apk` — clean Kokoro vs Supertonic benchmark UI.
- `Bolo-Supertonic-Engine-v0.1.apk` — companion Supertonic 3 LiteRT engine.

Install the companion engine, open it once, tap **Download / verify model**, then
return to Bolo and compare the same narration passage with both engines.

Bolo itself remains offline and has no network permission. The companion engine
uses network access only to fetch its model; synthesis is local afterward.

See `PHASE0_V0.16_CHECKPOINT.md`.
