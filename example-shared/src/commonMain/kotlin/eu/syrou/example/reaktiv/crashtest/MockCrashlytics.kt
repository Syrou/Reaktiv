package eu.syrou.example.reaktiv.crashtest

import io.github.syrou.reaktiv.core.util.ReaktivDebug

object MockCrashlytics {
    fun recordException(throwable: Throwable) {
        ReaktivDebug.general("MockCrashlytics: Recorded non-fatal exception: ${throwable::class.simpleName} - ${throwable.message}")
    }
}
