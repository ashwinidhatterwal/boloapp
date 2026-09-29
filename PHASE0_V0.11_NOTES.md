# Phase 0 v0.11 — Kitten Micro controlled test

No Bolo runtime code was changed in this iteration.

Added:
- independent GitHub Actions workflow for KittenTTS Micro 0.8
- pinned Kitten Android engine commit:
  e779f26498bb0d81294340ff565e321284a0abc8
- pinned espeak-ng release: 1.52.0
- official Micro ONNX SHA-256:
  95481626fee1ba70ce683e69c534fc7cb38433c46ce42d3abbeafb4b9f1a4123
- official Micro voices SHA-256:
  112710c1be8ad0e967c190fb0fd95cbe5848ec4791b93209f20b28b7da20dac1
- ARM64-only Micro engine packaging
- APK content checks before artifact upload

The existing Bolo benchmark remains frozen so this experiment changes one
meaningful variable: Nano model -> Micro model.
