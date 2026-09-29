# Phase 0 v0.9 — Pocket stage diagnostics

Why:
v0.8 timed out after 60 seconds but could not tell whether Pocket was stuck
loading its native model or actually synthesizing.

What v0.9 measures:
- request submitted
- native engine start / Android synthesis begin
- first audio callback
- completion
- elapsed time at timeout

Timeout is temporarily extended to 180 seconds for diagnosis.

Pocket English release defaults:
- temperature 0.3
- LSD steps 1
- threads 2
- sentence pause 250 ms
- max segment 50 tokens

For this device test, manually set Threads to 4 in Pocket TTS and Save.
Keep temperature 0.3, LSD 1, pause 250, segment 50.

Test order:
1. Pocket short test
2. If successful, Narration
3. Then 5x stress only after a single Narration pass completes

Interpretation:
- native-start = not reached -> model/native engine loading bottleneck
- native-start reached but first-audio not reached -> generation startup bottleneck
- first-audio reached but done not reached -> streaming/generation is simply too slow
