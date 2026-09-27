package io.github.syrou.reaktiv.introspection

import io.github.syrou.reaktiv.core.util.ReaktivDebug
import io.github.syrou.reaktiv.introspection.capture.SessionCapture
import kotlin.experimental.ExperimentalNativeApi
import kotlinx.cinterop.CFunction
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.invoke
import kotlinx.cinterop.staticCFunction
import kotlinx.coroutines.runBlocking
import platform.Foundation.NSException
import platform.Foundation.NSGetUncaughtExceptionHandler
import platform.Foundation.NSSetUncaughtExceptionHandler

@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)
public actual class CrashHandler actual constructor(
    private val platformContext: PlatformContext,
    private val sessionCapture: SessionCapture
) {
    public actual fun install() {
        State.sessionCapture = sessionCapture
        State.sessionFileExport = SessionFileExport(platformContext)
        if (State.installed) return
        State.installed = true

        State.previousExceptionHandler = NSGetUncaughtExceptionHandler()
        NSSetUncaughtExceptionHandler(staticCFunction { exception: NSException? ->
            if (exception != null) State.saveCrash(Exception(exception.reason ?: "Unknown NSException"))
            State.previousExceptionHandler?.invoke(exception)
            Unit
        })
        State.previousKotlinHook = setUnhandledExceptionHook { throwable ->
            State.saveCrash(throwable)
            State.previousKotlinHook?.invoke(throwable)
        }
        ReaktivDebug.general("Introspection: Crash handler installed (iOS)")
    }

    private companion object State {
        var sessionCapture: SessionCapture? = null
        var sessionFileExport: SessionFileExport? = null
        var installed = false
        var previousExceptionHandler: CPointer<CFunction<(NSException?) -> Unit>>? = null
        var previousKotlinHook: ReportUnhandledExceptionHook? = null

        fun saveCrash(throwable: Throwable) {
            try {
                val capture = sessionCapture ?: return
                val export = sessionFileExport ?: return
                runBlocking { export.saveSession(capture, throwable) }
            } catch (_: Exception) {
            }
        }
    }
}
