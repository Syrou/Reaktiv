package io.github.syrou.reaktiv.introspection

import io.github.syrou.reaktiv.introspection.capture.SessionCapture
import kotlin.time.Clock

actual class CrashHandler actual constructor(
    private val platformContext: PlatformContext,
    private val sessionCapture: SessionCapture
) {
    actual fun install() {
        if (installed) {
            println("Introspection: Crash handler already installed")
            return
        }

        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        val sessionExport = SessionFileExport(platformContext)

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val json = sessionCapture.exportCrashSession(throwable)
                val timestamp = Clock.System.now().toEpochMilliseconds()
                val fileName = "reaktiv_crash_$timestamp.json"
                val savedPath = sessionExport.saveToDownloads(json, fileName)
                println("Introspection: Crash session saved to $savedPath")
            } catch (e: Exception) {
                println("Introspection: Failed to save crash session - ${e.message}")
            } finally {
                previousHandler?.uncaughtException(thread, throwable)
            }
        }

        installed = true
        println("Introspection: Crash handler installed")
    }

    companion object {
        private var installed = false
    }
}
