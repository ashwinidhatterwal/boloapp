from pathlib import Path


# Local Bolo builds only need :library:assembleRelease.
# Strip Kokoro's Maven Central publishing plugin/config so CI does not depend
# on com.vanniktech.maven.publish just to create a local AAR.
root_build = Path("build.gradle.kts")
root_code = root_build.read_text()
publish_root_line = "    alias(libs.plugins.vanniktech.publish) apply false\n"
if publish_root_line in root_code:
    root_code = root_code.replace(publish_root_line, "")
root_build.write_text(root_code)

library_build = Path("library/build.gradle.kts")
library_code = library_build.read_text()
publish_library_line = "    alias(libs.plugins.vanniktech.publish)\n"
if publish_library_line in library_code:
    library_code = library_code.replace(publish_library_line, "")

publish_marker = "\nmavenPublishing {"
publish_index = library_code.find(publish_marker)
if publish_index >= 0:
    # mavenPublishing is the final top-level block in the pinned upstream file.
    library_code = library_code[:publish_index].rstrip() + "\n"

library_build.write_text(library_code)

# Fail early if the unnecessary publishing plugin is still referenced by a
# build script. (The version-catalog declaration itself is harmless when unused.)
remaining = root_build.read_text() + "\n" + library_build.read_text()
if "libs.plugins.vanniktech.publish" in remaining or "mavenPublishing {" in remaining:
    raise SystemExit("Failed to strip Kokoro Maven publishing configuration")


versions = Path("gradle/libs.versions.toml")
text = versions.read_text()
old = 'onnxruntime = "1.20.0"'
new = 'onnxruntime = "1.30.0"'
if old not in text:
    raise SystemExit("Expected ORT 1.20.0 pin not found")
versions.write_text(text.replace(old, new))

src = Path("library/src/main/kotlin/dev/ffmpegkit/kokoro/KokoroTTS.kt")
code = src.read_text()

# Add a small public runtime profile enum beside KokoroTTS. Bolo can switch
# profiles without changing the TTS engine abstraction or the model.
object_marker = "object KokoroTTS {\n"
if object_marker not in code:
    raise SystemExit("KokoroTTS object marker not found")

profile_enum = """enum class KokoroRuntimeProfile {
    CPU_BASELINE,
    CPU_OPTIMIZED,
    XNNPACK_4,
    XNNPACK_6,
    XNNPACK_8,
}

"""

code = code.replace(object_marker, profile_enum + object_marker, 1)

# Keep SessionOptions alive for the full session lifetime.
session_marker = "    private var session: OrtSession? = null\n"
if session_marker not in code:
    raise SystemExit("Kokoro session field marker not found")
code = code.replace(
    session_marker,
    session_marker + "    private var sessionOptions: OrtSession.SessionOptions? = null\n",
    1,
)

# Extend initialize() with a backwards-compatible runtime profile argument.
old_signature = """    suspend fun initialize(
        context: Context,
        modelPath: String,
        voice: KokoroVoice = KokoroVoice.AF_HEART,
    ): Boolean = withContext(Dispatchers.Default) {
"""
new_signature = """    suspend fun initialize(
        context: Context,
        modelPath: String,
        voice: KokoroVoice = KokoroVoice.AF_HEART,
        runtimeProfile: KokoroRuntimeProfile = KokoroRuntimeProfile.CPU_BASELINE,
    ): Boolean = withContext(Dispatchers.Default) {
"""
if old_signature not in code:
    raise SystemExit("Kokoro initialize signature not found")
code = code.replace(old_signature, new_signature, 1)

old_create = """        val e = OrtEnvironment.getEnvironment()
        session = runCatching { e.createSession(modelPath, OrtSession.SessionOptions()) }
            .getOrElse { throw KokoroException.ModelLoadFailed(modelPath, it) }
        env = e
"""

new_create = """        val e = OrtEnvironment.getEnvironment()
        val availableCores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val options = OrtSession.SessionOptions().apply {
            setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
            setInterOpNumThreads(1)
            setCPUArenaAllocator(true)
            setMemoryPatternOptimization(true)

            when (runtimeProfile) {
                KokoroRuntimeProfile.CPU_BASELINE -> {
                    // This reproduces the known-good v0.7+ baseline.
                    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
                    setIntraOpNumThreads(availableCores.coerceAtMost(4))
                }

                KokoroRuntimeProfile.CPU_OPTIMIZED -> {
                    // FP32-only experiment: q8f16 is no longer part of this path.
                    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                    setIntraOpNumThreads(availableCores.coerceAtMost(8))
                    addConfigEntry("session.intra_op.allow_spinning", "0")
                    addConfigEntry("session.inter_op.allow_spinning", "0")
                }

                KokoroRuntimeProfile.XNNPACK_4,
                KokoroRuntimeProfile.XNNPACK_6,
                KokoroRuntimeProfile.XNNPACK_8 -> {
                    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)

                    // ONNX Runtime recommends keeping ORT intra-op at 1 when
                    // XNNPACK owns a separate private thread pool.
                    setIntraOpNumThreads(1)
                    addConfigEntry("session.intra_op.allow_spinning", "0")
                    addConfigEntry("session.inter_op.allow_spinning", "0")

                    val requestedThreads = when (runtimeProfile) {
                        KokoroRuntimeProfile.XNNPACK_4 -> 4
                        KokoroRuntimeProfile.XNNPACK_6 -> 6
                        KokoroRuntimeProfile.XNNPACK_8 -> 8
                        else -> 4
                    }
                    val xnnpackThreads = requestedThreads.coerceAtMost(availableCores)
                    addXnnpack(
                        mapOf("intra_op_num_threads" to xnnpackThreads.toString())
                    )
                }
            }
        }

        session = runCatching { e.createSession(modelPath, options) }
            .getOrElse {
                runCatching { options.close() }
                throw KokoroException.ModelLoadFailed(modelPath, it)
            }
        sessionOptions = options
        env = e
"""

if old_create not in code:
    raise SystemExit("Kokoro session creation block not found")
code = code.replace(old_create, new_create, 1)

old_release = """        runCatching { session?.close() }; session = null
        env = null
"""
new_release = """        runCatching { session?.close() }; session = null
        runCatching { sessionOptions?.close() }; sessionOptions = null
        env = null
"""
if old_release not in code:
    raise SystemExit("Kokoro release block not found")
code = code.replace(old_release, new_release, 1)

src.write_text(code)
