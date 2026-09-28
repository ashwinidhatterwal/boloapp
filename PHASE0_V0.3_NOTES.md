# Phase 0 v0.3

This is the complete Offline Narrator / Bolo Phase-0 benchmark package.

## Why v0.3 exists

GitHub Actions run #3 reached Gradle dependency resolution and failed with:

`Could not find dev.ffmpegkit-maintained:kokoro-android:0.1.0`

The upstream Kokoro project documents that Maven coordinate, but the artifact was not
resolvable from the repositories available to our build.

## Fix

This version does not depend on that published artifact.

GitHub Actions now:
1. installs API 36, Build Tools 36.0.0, NDK r27c and CMake 3.22.1;
2. installs `espeak-ng-data`;
3. clones the upstream `ffmpegkit-maintained/kokoro-android` source;
4. builds the free Kokoro Android AAR;
5. verifies that the AAR contains `libkokoro_jni.so`;
6. copies it to `app/libs/kokoro-android.aar`;
7. runs our unit tests and builds the Bolo APK.

The app depends on the generated local AAR, plus explicit ONNX Runtime/Core KTX
dependencies that a normal Maven publication would otherwise provide transitively.

The replaceable `TtsEngine` architecture is unchanged.
