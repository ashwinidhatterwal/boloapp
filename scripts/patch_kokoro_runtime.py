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
    CPU_ALL_2,
    CPU_ALL_4,
    CPU_ALL_6,
    CPU_ALL_8,
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
                    // Known-good reference profile from the earlier benchmark.
                    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
                    setIntraOpNumThreads(availableCores.coerceAtMost(4))
                }

                KokoroRuntimeProfile.CPU_ALL_2,
                KokoroRuntimeProfile.CPU_ALL_4,
                KokoroRuntimeProfile.CPU_ALL_6,
                KokoroRuntimeProfile.CPU_ALL_8 -> {
                    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)

                    val requestedThreads = when (runtimeProfile) {
                        KokoroRuntimeProfile.CPU_ALL_2 -> 2
                        KokoroRuntimeProfile.CPU_ALL_4 -> 4
                        KokoroRuntimeProfile.CPU_ALL_6 -> 6
                        KokoroRuntimeProfile.CPU_ALL_8 -> 8
                        else -> 4
                    }

                    setIntraOpNumThreads(requestedThreads.coerceAtMost(availableCores))
                    setInterOpNumThreads(1)

                    // Keep spinning disabled for this checkpoint so thread count
                    // is the only changing performance variable.
                    addConfigEntry("session.intra_op.allow_spinning", "0")
                    addConfigEntry("session.inter_op.allow_spinning", "0")
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


# Kokoro's encoder has a finite context window. The Android wrapper previously
# sent the entire text box as one inference call, which can fail inside BERT's
# Expand node for long passages. Segment by the *actual Kokoro phoneme-token
# count* before inference. Keep a margin below the upstream hard ceiling.
style_marker = "    private const val STYLE_DIM = 256   // Kokoro style vector size\n"
if style_marker not in code:
    raise SystemExit("Kokoro STYLE_DIM marker not found")
code = code.replace(
    style_marker,
    style_marker + "    private const val MAX_MODEL_TOKENS = 500\n",
    1,
)

old_speak = """    /** Synthesize [text] to audio using the current voice. */
    suspend fun speak(text: String, config: KokoroConfig = KokoroConfig()): KokoroResult =
        withContext(Dispatchers.Default) {
            val sess = session ?: throw KokoroException.NotInitialized()
            val e = env ?: throw KokoroException.NotInitialized()

            val phonemes = KokoroJNI.nativePhonemize(text)
            val tokens = KokoroVocab.encode(phonemes)
            val style = styleFor(voice.id, tokens.size)
            val pcm = runModel(e, sess, tokens, style, config.speed)

            val audio = when (config.outputFormat) {
                AudioFormat.PCM -> floatToPcm16(pcm)
                else -> encodeWav(floatToPcm16(pcm), config.sampleRate)   // WAV (Free)
            }
            KokoroResult(
                audioData = audio,
                durationMs = (pcm.size * 1000L) / config.sampleRate,
                sampleRate = config.sampleRate,
                format = if (config.outputFormat == AudioFormat.PCM) AudioFormat.PCM else AudioFormat.WAV,
            )
        }
"""

new_speak = """    /** Synthesize [text] to audio using the current voice.
     *
     * Long passages are segmented automatically before ONNX inference. Each
     * segment stays below the model's phoneme-token context limit and the PCM
     * pieces are joined into one normal result for the caller.
     */
    suspend fun speak(text: String, config: KokoroConfig = KokoroConfig()): KokoroResult =
        withContext(Dispatchers.Default) {
            val sess = session ?: throw KokoroException.NotInitialized()
            val e = env ?: throw KokoroException.NotInitialized()
            if (text.isBlank()) throw KokoroException.SynthesisFailed("text is blank")

            val chunks = chunkTextForModel(text)
            val pcmBuffer = ByteArrayOutputStream()
            var totalSamples = 0L

            for (chunk in chunks) {
                val phonemes = KokoroJNI.nativePhonemize(chunk)
                val tokens = KokoroVocab.encode(phonemes)

                if (tokens.size > MAX_MODEL_TOKENS) {
                    throw KokoroException.SynthesisFailed(
                        "internal chunk exceeded Kokoro token limit: ${tokens.size}"
                    )
                }

                val style = styleFor(voice.id, tokens.size)
                val pcm = runModel(e, sess, tokens, style, config.speed)
                totalSamples += pcm.size.toLong()
                pcmBuffer.write(floatToPcm16(pcm))
            }

            val pcm16 = pcmBuffer.toByteArray()
            val audio = when (config.outputFormat) {
                AudioFormat.PCM -> pcm16
                else -> encodeWav(pcm16, config.sampleRate)
            }

            KokoroResult(
                audioData = audio,
                durationMs = (totalSamples * 1000L) / config.sampleRate,
                sampleRate = config.sampleRate,
                format = if (config.outputFormat == AudioFormat.PCM) AudioFormat.PCM else AudioFormat.WAV,
            )
        }
"""

if old_speak not in code:
    raise SystemExit("Kokoro speak block not found")
code = code.replace(old_speak, new_speak, 1)

internals_marker = "    // --- internals ---------------------------------------------------------\n"
if internals_marker not in code:
    raise SystemExit("Kokoro internals marker not found")

helpers = """    /**
     * Split arbitrary text into model-safe pieces using the same phoneme/token
     * pipeline that will actually feed ONNX.
     */
    private fun chunkTextForModel(text: String): List<String> {
        val normalized = text
            .replace(Regex("[ \\\\t]+"), " ")
            .trim()

        if (normalized.isEmpty()) return emptyList()
        if (tokenCount(normalized) <= MAX_MODEL_TOKENS) return listOf(normalized)

        val units = Regex("(?<=[.!?])\\\\s+|[\\\\r\\\\n]+")
            .split(normalized)
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        val chunks = mutableListOf<String>()
        var current = ""

        fun flushCurrent() {
            if (current.isNotBlank()) {
                chunks += current.trim()
                current = ""
            }
        }

        for (unit in units) {
            if (tokenCount(unit) > MAX_MODEL_TOKENS) {
                flushCurrent()
                chunks += splitOversizedUnit(unit)
                continue
            }

            val candidate = if (current.isBlank()) unit else "$current $unit"
            if (tokenCount(candidate) <= MAX_MODEL_TOKENS) {
                current = candidate
            } else {
                flushCurrent()
                current = unit
            }
        }

        flushCurrent()

        if (chunks.isEmpty()) {
            throw KokoroException.SynthesisFailed("unable to segment text")
        }
        return chunks
    }

    private fun tokenCount(text: String): Int {
        if (text.isBlank()) return 0
        val phonemes = KokoroJNI.nativePhonemize(text)
        return KokoroVocab.encode(phonemes).size
    }

    /**
     * A single sentence can itself exceed the context window. Find the largest
     * safe prefix by token count, then back up to a nearby readable boundary.
     */
    private fun splitOversizedUnit(text: String): List<String> {
        val out = mutableListOf<String>()
        var remaining = text.trim()

        while (remaining.isNotEmpty()) {
            if (tokenCount(remaining) <= MAX_MODEL_TOKENS) {
                out += remaining
                break
            }

            var low = 1
            var high = remaining.length
            var best = 0

            while (low <= high) {
                val mid = (low + high) ushr 1
                val candidate = remaining.substring(0, mid)

                if (tokenCount(candidate) <= MAX_MODEL_TOKENS) {
                    best = mid
                    low = mid + 1
                } else {
                    high = mid - 1
                }
            }

            if (best <= 0) {
                throw KokoroException.SynthesisFailed(
                    "unable to create a model-safe Kokoro chunk"
                )
            }

            var cut = best
            if (best < remaining.length) {
                val prefix = remaining.substring(0, best)
                val naturalBoundary = prefix.lastIndexOfAny(
                    charArrayOf(' ', ',', ';', ':', '-', '—')
                )
                if (naturalBoundary >= best / 2) {
                    cut = naturalBoundary + 1
                }
            }

            val piece = remaining.substring(0, cut).trim()
            if (piece.isEmpty()) {
                throw KokoroException.SynthesisFailed(
                    "Kokoro segmentation made no progress"
                )
            }

            if (tokenCount(piece) > MAX_MODEL_TOKENS) {
                throw KokoroException.SynthesisFailed(
                    "Kokoro chunk remained above token limit"
                )
            }

            out += piece
            remaining = remaining.substring(cut).trimStart()
        }

        return out
    }

"""

code = code.replace(internals_marker, internals_marker + "\n" + helpers, 1)

src.write_text(code)
