# Validation — Bolo v0.26

- Real pinned Kokoro library: `:library:assembleRelease` **passed**.
- App Kotlin compilation: `:app:compileDebugKotlin` **passed**.
- Android JVM tests: **60 passed, 0 failures, 0 errors**.
- Debug APK assembly: `:app:assembleDebug` **passed**.
- Android lint: `:app:lintDebug` **passed**, 0 errors, 32 warnings.
- Workflow shell blocks: syntax checked with `bash -n`.
- APK inspected for ARM64 Kokoro native library, five voices and eSpeak English dictionary.
- Gradle wrapper generated from 9.6.0 with its official distribution checksum.

Tests include contractions/plural possessives in dialogue, original word-accounting,
long-token fragmentation, quote/attribution continuity, negated and merely mentioned
emotion, scene-safe short dialogue batching, bounded seek interpolation, malformed
WAV rejection, intentional chapter pause handling, and semantic annotation validation.
Semantic tests use a fake provider: grounded evidence, invented evidence/names,
missing annotations and disabled-provider behavior.

Lint warnings chiefly concern newer available dependencies/SDK, the existing ARM64
only target, shared-preference style, application icon, exported media service,
and transitive dependency code. No warnings were hidden to obtain the passing result.

## Device acceptance still required

1. Prepare the listening fixture in docs/qa with offline mode, then your chosen
   semantic provider. Listen for unwanted pauses, misattribution, pronunciation,
   omissions/repetitions and level changes.
2. Stop/reopen during preparation: completed chunks should be reused. Kill the
   process during a scheduled charging-only job and confirm WorkManager resumes it.
3. Seek backwards/forwards while playing and paused; sentence taps should play
   from the selected approximate anchor without fresh inference.
4. Prepare three chapters. Confirm automatic prepared transitions and that an
   unprepared chapter waits for preparation instead of running Kokoro during listening.
5. Check locked-screen/headset controls, AAC boundary artifacts, battery usage,
   thermal waits and low-storage recovery on the target phone.
6. Enable semantic direction with invalid credentials: confirm explicit offline
   fallback, preserved original text and no secret values in diagnostics.

No physical-device runtime, live semantic-model quality, numerical battery saving,
word-perfect speech alignment or automatic ASR proofing is claimed by these checks.
