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
