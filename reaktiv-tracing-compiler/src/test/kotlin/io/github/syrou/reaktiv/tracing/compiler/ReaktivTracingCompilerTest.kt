package io.github.syrou.reaktiv.tracing.compiler

import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReaktivTracingCompilerTest {

    private class Compilation(val exitCode: ExitCode, val output: String, val classes: File) {
        fun classText(name: String): String =
            File(classes, name.replace('.', '/') + ".class").readBytes().toString(Charsets.ISO_8859_1)
    }

    private val pluginJar: String = requireNotNull(System.getProperty("reaktiv.tracing.pluginJar")) {
        "The test task passes the plugin jar as reaktiv.tracing.pluginJar"
    }

    private val stdlib: String = File(KotlinVersion::class.java.protectionDomain.codeSource.location.toURI()).absolutePath

    private fun compile(sources: Map<String, String>, classpath: List<File> = emptyList(), withPlugin: Boolean = true): Compilation {
        val root = Files.createTempDirectory("reaktiv-tracing").toFile()
        val sourceDir = File(root, "src").apply { mkdirs() }
        val files = sources.map { (name, text) -> File(sourceDir, name).apply { writeText(text) } }
        val classes = File(root, "classes")
        val output = ByteArrayOutputStream()
        val arguments = buildList {
            add("-no-stdlib")
            add("-no-reflect")
            add("-classpath")
            add((listOf(stdlib) + classpath.map { it.absolutePath }).joinToString(File.pathSeparator))
            add("-d")
            add(classes.absolutePath)
            if (withPlugin) add("-Xplugin=$pluginJar")
            addAll(files.map { it.absolutePath })
        }
        val exitCode = K2JVMCompiler().exec(PrintStream(output), *arguments.toTypedArray())
        return Compilation(exitCode, output.toString(), classes)
    }

    private fun runtime(vararg sources: Pair<String, String>): File {
        val compilation = compile(mapOf(CORE_STUBS, TIME_STUB) + sources, withPlugin = false)
        assertEquals(ExitCode.OK, compilation.exitCode, compilation.output)
        return compilation.classes
    }

    @Test
    fun `a ModuleLogic subclass is instrumented against a matching runtime`() {
        val compilation = compile(mapOf(SAMPLE_LOGIC), classpath = listOf(runtime(TRACER_STUB, ORIGIN_STUB)))

        assertEquals(ExitCode.OK, compilation.exitCode, compilation.output)
        assertTrue("notifyMethodStart" in compilation.classText("sample.SampleLogic"))
    }

    @Test
    fun `a class that only shares the ModuleLogic name is left alone`() {
        val compilation = compile(mapOf(LOOKALIKE_LOGIC), classpath = listOf(runtime(TRACER_STUB, ORIGIN_STUB)))

        assertEquals(ExitCode.OK, compilation.exitCode, compilation.output)
        assertFalse("notifyMethodStart" in compilation.classText("sample.LookalikeLogic"))
    }

    @Test
    fun `a Dispatch value records its origin whatever it is called`() {
        val compilation = compile(mapOf(SENDER), classpath = listOf(runtime(TRACER_STUB, ORIGIN_STUB)))

        assertEquals(ExitCode.OK, compilation.exitCode, compilation.output)
        assertTrue("DispatchOriginTracker" in compilation.classText("sample.Sender"))
        assertFalse("DispatchOriginTracker" in compilation.classText("sample.Unrelated"))
    }

    @Test
    fun `a missing tracing runtime fails the build and names the artifact`() {
        val compilation = compile(mapOf(SAMPLE_LOGIC), classpath = listOf(runtime(ORIGIN_STUB)))

        assertEquals(ExitCode.COMPILATION_ERROR, compilation.exitCode, compilation.output)
        assertTrue("reaktiv-tracing-runtime" in compilation.output, compilation.output)
    }

    @Test
    fun `a runtime with another signature fails the build and names the function`() {
        val compilation = compile(mapOf(SAMPLE_LOGIC), classpath = listOf(runtime(OLD_TRACER_STUB, ORIGIN_STUB)))

        assertEquals(ExitCode.COMPILATION_ERROR, compilation.exitCode, compilation.output)
        assertTrue("notifyMethodStart" in compilation.output, compilation.output)
    }

    private companion object {
        val CORE_STUBS = "Core.kt" to """
            package io.github.syrou.reaktiv.core

            abstract class ModuleLogic
            abstract class ModuleAction
        """.trimIndent()

        val TIME_STUB = "Time.kt" to """
            package io.github.syrou.reaktiv.core.util

            fun currentTimeMillis(): Long = 0L
        """.trimIndent()

        val TRACER_STUB = "LogicTracer.kt" to """
            package io.github.syrou.reaktiv.core.tracing

            object LogicTracer {
                val active: Boolean get() = false

                suspend fun notifyMethodStart(
                    logicClass: String,
                    methodName: String,
                    params: Map<String, String>,
                    sourceFile: String? = null,
                    lineNumber: Int? = null,
                    githubSourceUrl: String? = null,
                    startedAtMs: Long? = null,
                    redactions: Map<String, String>? = null
                ): String = ""

                fun notifyMethodCompleted(callId: String, result: String?, resultType: String, durationMs: Long) {}

                fun notifyMethodFailed(callId: String, exception: Throwable, durationMs: Long) {}
            }
        """.trimIndent()

        val OLD_TRACER_STUB = "LogicTracer.kt" to """
            package io.github.syrou.reaktiv.core.tracing

            object LogicTracer {
                val active: Boolean get() = false

                suspend fun notifyMethodStart(
                    logicClass: String,
                    methodName: String,
                    params: Map<String, String>,
                    sourceFile: String? = null,
                    lineNumber: Int? = null,
                    githubSourceUrl: String? = null
                ): String = ""

                fun notifyMethodCompleted(callId: String, result: String?, resultType: String, durationMs: Long) {}

                fun notifyMethodFailed(callId: String, exception: Throwable, durationMs: Long) {}
            }
        """.trimIndent()

        val ORIGIN_STUB = "DispatchOriginTracker.kt" to """
            package io.github.syrou.reaktiv.core.tracing

            object DispatchOriginTracker {
                fun record(action: Any, origin: String) {}
            }
        """.trimIndent()

        val SAMPLE_LOGIC = "SampleLogic.kt" to """
            package sample

            import io.github.syrou.reaktiv.core.ModuleLogic

            class SampleLogic : ModuleLogic() {
                suspend fun load(id: Int): String = "item-${'$'}id"
            }
        """.trimIndent()

        val LOOKALIKE_LOGIC = "LookalikeLogic.kt" to """
            package sample

            abstract class ModuleLogicLookalike

            class LookalikeLogic : ModuleLogicLookalike() {
                suspend fun load(id: Int): String = "item-${'$'}id"
            }
        """.trimIndent()

        val SENDER = "Sender.kt" to """
            package sample

            import io.github.syrou.reaktiv.core.ModuleAction

            class Sender(private val send: (ModuleAction) -> Unit) {
                fun forward(action: ModuleAction) = send(action)
            }

            class Unrelated(private val dispatch: (String) -> Unit) {
                fun forward(text: String) = dispatch(text)
            }
        """.trimIndent()
    }
}
