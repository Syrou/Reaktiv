package io.github.syrou.reaktiv.introspection

import io.github.syrou.reaktiv.core.util.ReaktivDebug
import io.github.syrou.reaktiv.introspection.capture.SessionCapture
import kotlinx.coroutines.runBlocking

public actual class CrashHandler actual constructor(
    private val platformContext: PlatformContext,
    private val sessionCapture: SessionCapture
) {
    public actual fun install() {
        current = sessionCapture to SessionFileExport(platformContext)
        if (installed) {
            ReaktivDebug.general("Introspection: Crash handler now reports to the newest capture")
            return
        }

        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                current?.let { (capture, export) ->
                    val savedPath = runBlocking { export.saveSession(capture, throwable) }
                    println("Introspection: Crash session saved to $savedPath")
                }
            } catch (e: Exception) {
                println("Introspection: Failed to save crash session - ${e.message}")
            } finally {
                previousHandler?.uncaughtException(thread, throwable)
            }
        }

        installed = true
        ReaktivDebug.general("Introspection: Crash handler installed")
    }

    public companion object {
        @Volatile
        private var current: Pair<SessionCapture, SessionFileExport>? = null
        private var installed = false
    }
}
