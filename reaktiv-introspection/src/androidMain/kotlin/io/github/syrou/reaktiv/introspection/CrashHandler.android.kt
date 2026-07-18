package io.github.syrou.reaktiv.introspection

import io.github.syrou.reaktiv.core.util.ReaktivDebug
import io.github.syrou.reaktiv.core.util.currentTimeMillis
import io.github.syrou.reaktiv.introspection.capture.SessionCapture
import kotlinx.coroutines.runBlocking

public actual class CrashHandler actual constructor(
    private val platformContext: PlatformContext,
    private val sessionCapture: SessionCapture
) {
    public actual fun install() {
        if (installed) {
            ReaktivDebug.general("Introspection: Crash handler already installed")
            return
        }

        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        val sessionExport = SessionFileExport(platformContext)

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val json = runBlocking { sessionCapture.exportCrashSession(throwable) }
                val fileName = sessionCapture.suggestFileName("crash")
                val savedPath = sessionExport.saveToDownloads(json, fileName)
                println("Introspection: Crash session saved to $savedPath")
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
        private var installed = false
    }
}
