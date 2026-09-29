from pathlib import Path

versions = Path("gradle/libs.versions.toml")
text = versions.read_text()
old = 'onnxruntime = "1.20.0"'
new = 'onnxruntime = "1.30.0"'
if old not in text:
    raise SystemExit("Expected ORT 1.20.0 pin not found")
versions.write_text(text.replace(old, new))

src = Path("library/src/main/kotlin/dev/ffmpegkit/kokoro/KokoroTTS.kt")
code = src.read_text()

marker = '    private var session: OrtSession? = null\n'
if marker not in code:
    raise SystemExit("Kokoro session field marker not found")
code = code.replace(
    marker,
    marker + '    private var sessionOptions: OrtSession.SessionOptions? = null\n',
    1,
)

old_create = (
    '        val e = OrtEnvironment.getEnvironment()\n'
    '        session = runCatching { e.createSession(modelPath, OrtSession.SessionOptions()) }\n'
    '            .getOrElse { throw KokoroException.ModelLoadFailed(modelPath, it) }\n'
    '        env = e\n'
)
new_create = (
    '        val e = OrtEnvironment.getEnvironment()\n'
    '        val options = OrtSession.SessionOptions().apply {\n'
    '            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)\n'
    '            setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)\n'
    '            setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(1, 4))\n'
    '            setInterOpNumThreads(1)\n'
    '            setCPUArenaAllocator(true)\n'
    '            setMemoryPatternOptimization(true)\n'
    '        }\n'
    '        session = runCatching { e.createSession(modelPath, options) }\n'
    '            .getOrElse {\n'
    '                runCatching { options.close() }\n'
    '                throw KokoroException.ModelLoadFailed(modelPath, it)\n'
    '            }\n'
    '        sessionOptions = options\n'
    '        env = e\n'
)
if old_create not in code:
    raise SystemExit("Kokoro session creation block not found")
code = code.replace(old_create, new_create, 1)

old_release = (
    '        runCatching { session?.close() }; session = null\n'
    '        env = null\n'
)
new_release = (
    '        runCatching { session?.close() }; session = null\n'
    '        runCatching { sessionOptions?.close() }; sessionOptions = null\n'
    '        env = null\n'
)
if old_release not in code:
    raise SystemExit("Kokoro release block not found")
code = code.replace(old_release, new_release, 1)

src.write_text(code)
