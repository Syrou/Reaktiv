package io.github.syrou.reaktiv.introspection

import io.github.syrou.reaktiv.introspection.capture.SessionCapture

/**
 * Platform-specific crash handler that captures session data on uncaught exceptions.
 *
 * On Android, installs a [Thread.UncaughtExceptionHandler] that saves crash
 * session data to the Downloads folder via [SessionFileExport].
 * On iOS, uses `NSSetUncaughtExceptionHandler` to capture crash sessions.
 * On other platforms, this is a no-op.
 *
 * Usage:
 * ```kotlin
 * CrashHandler(platformContext, sessionCapture).install()
 * ```
 *
 * @param platformContext The platform context for file access
 * @param sessionCapture The session capture instance for recording events
 */
expect class CrashHandler(platformContext: PlatformContext, sessionCapture: SessionCapture) {
    fun install()
}
