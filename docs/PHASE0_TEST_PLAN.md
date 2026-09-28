# Phase 0 Test Plan

## Goal

Select a provisional local TTS engine based on actual Android performance, not demos.

## First test sequence

1. Install the debug APK.
2. Run the System baseline once.
3. Select Kokoro.
4. Download a compatible Kokoro v1.0 ONNX model in the browser.
5. Import the `.onnx` file in the app.
6. Run Narration.
7. Listen to the result at 1.0x.
8. Record RTF, PSS memory, native heap, battery temperature and thermal status.
9. Run Dialogue.
10. Run Numbers.
11. Run the 5× stress test.
12. Turn on airplane mode and repeat one synthesis to confirm no hidden cloud dependency.

## Acceptance guidance

- RTF < 1.0: generation can theoretically stay ahead of playback.
- RTF 0.5: roughly 2× realtime.
- RTF 1.5: slower than realtime; buffering/pre-generation would be required.

Do not select a production engine from RTF alone. Long-form listening quality matters.

## Report back

Send screenshots of:

- Narration result;
- Dialogue result;
- Numbers result;
- 5× stress result;
- any crash/error.

Also describe whether the voice is comfortable for at least 5–10 minutes.
