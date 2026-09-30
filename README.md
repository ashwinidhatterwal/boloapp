# Bolo — Audiobook Studio v0.26

Prepare an audiobook before listening, using Kokoro on your Android phone.

- Import EPUB/PDF/DOCX/TXT/HTML and the Kokoro ONNX model as before.
- Press Play to prepare the selected chapter, then listen using ordinary Media3 playback.
- Reader ••• → Prepare ahead: Chapter / Next 3 / Book; charging-only is the default.
- Prepared chapters survive restarts and use compressed M4A where supported.
- Seeking uses a stable prepared timeline. Completed next chapters advance automatically.
- Reader ••• → Narration intelligence & pronunciation enables an optional semantic
  provider or a manual pronunciation dictionary. Offline planning is the default.

The semantic provider receives book text only after you explicitly enable it. It
analyses meaning and intent; Kokoro applies subtle speed, level and pause changes.
There is no claim of perfect emotional acting or automatic word-perfect proofing.

## GitHub

Upload the contents of `boloapp/` to the repository root, including `.github/`,
`gradle/`, `gradlew` and `gradlew.bat`. Keep the existing package/application ID.
Push to main/develop or run **Build Android Debug APK** manually.

APK artifact: `bolo-v0.26-audiobook-studio`.
Reports artifact: `bolo-v0.26-validation-reports`.

For local builds, build the pinned Kokoro AAR using the commands in the workflow
and put it at `app/libs/kokoro-android.aar`, then run:

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

Read BOLO_V0.26_CHECKPOINT.md for implementation details and VALIDATION.md for
verification evidence and remaining device checks.
