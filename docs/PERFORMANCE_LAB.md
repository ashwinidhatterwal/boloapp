# Bolo Kokoro Performance Lab

The lab is designed to replace repeated manual profile testing.

## Before running

1. Import the normal Kokoro FP32 model as before.
2. Optional: download and import the official Kokoro v1.0 FP16 model:
   `onnx/model_fp16.onnx`
3. Keep TTS speed at 1.00x.
4. Let the phone return close to normal temperature before starting.
5. Avoid games, camera processing or other heavy background work during the run.

The app itself still declares no network permission. Model downloads happen in
the browser; Bolo imports the selected file into private app storage.

## Run

Quality -> Advanced -> Kokoro Performance Lab -> Run full Kokoro suite

The lab first runs a quick matrix:

- FP32 / CPU 2
- FP32 / CPU 4
- FP32 / CPU 6
- FP32 / CPU 8
- and the same four profiles for FP16 when FP16 is installed.

Each matrix row records RTF, RSS/PSS, temperature and thermal state. A failed
FP16 profile does not stop the FP32 tests.

After the matrix, Bolo chooses the fastest successful measured configuration and
runs:

- 5x narration stress validation
- long-form token-aware synthesis
- final memory and thermal snapshot

The winning validation audio is left available to Play so FP16 quality can be
compared by ear with the familiar FP32 voice.

## Interpretation

- RTF < 1.00: generation faster than 1x playback.
- RTF <= ~0.67: theoretical steady-state requirement for 1.5x playback.
- RTF around 0.55-0.60: preferred engineering headroom for 1.5x playback.
- RSS/PSS are the memory values to take most seriously.
- Native allocated is allocator accounting and is not the same thing as
  resident RAM.

The final reader will use buffering and pre-generation, so raw RTF is not the
only factor in perceived playback smoothness.
