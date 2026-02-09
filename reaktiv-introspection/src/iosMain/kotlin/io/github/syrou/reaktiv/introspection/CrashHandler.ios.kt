package io.github.syrou.reaktiv.introspection

import io.github.syrou.reaktiv.introspection.capture.SessionCapture
import kotlinx.cinterop.staticCFunction
import platform.Foundation.NSException
import platform.Foundation.NSSetUncaughtExceptionHandler
import kotlin.time.Clock

actual class CrashHandler actual constructor(
    private val platformContext: PlatformContext,
    private val sessionCapture: SessionCapture
) {
    actual fun install() {
        State.sessionCapture = sessionCapture
        State.sessionFileExport = SessionFileExport(platformContext)

        NSSetUncaughtExceptionHandler(staticCFunction { exception: NSException? ->
            if (exception != null) {
                try {
                    val capture = State.sessionCapture ?: return@staticCFunction
                    val exporter = State.sessionFileExport ?: return@staticCFunction
                    val throwable = Exception(exception.reason ?: "Unknown NSException")
                    val json = capture.exportCrashSession(throwable)
                    val timestamp = Clock.System.now().toEpochMilliseconds()
                    val fileName = "reaktiv_crash_$timestamp.json"
                    exporter.saveToDownloads(json, fileName)
                } catch (_: Exception) {
                    // Best effort during crash
                }
            }
        })
        println("Introspection: Crash handler installed (iOS)")
    }

    private companion object State {
        var sessionCapture: SessionCapture? = null
        var sessionFileExport: SessionFileExport? = null
    }
}
