package io.github.syrou.reaktiv.core

import kotlin.reflect.KClass

@RequiresOptIn(
    message = "This API is for specialized DevTools and testing use only. " +
            "Using it in application code bypasses MVLI patterns and is strongly discouraged.",
    level = RequiresOptIn.Level.WARNING
)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
public annotation class ExperimentalReaktivApi

/**
 * Determines how the Store should handle a crash after listeners are notified.
 */
public enum class CrashRecovery {
    /**
     * Navigate to crash screen and do NOT re-throw.
     * The developer is responsible for reporting to Crashlytics via recordException().
     */
    NAVIGATE_TO_CRASH_SCREEN,

    /**
     * Let the crash propagate normally. Default behavior.
     */
    RETHROW
}

/**
 * Listener for crashes that occur during logic execution in the Store.
 *
 * Implementations can handle crash recovery (e.g., navigating to a crash screen)
 * and return a [CrashRecovery] to control whether the exception is re-thrown.
 *
 * The [action] parameter is provided for context when a crash is associated with a
 * specific action dispatch, and null when the crash occurred in a coroutine launched
 * via `storeAccessor.launch` from a logic method.
 */
@ExperimentalReaktivApi
public interface CrashListener {
    public suspend fun onLogicCrash(exception: Throwable, action: ModuleAction?): CrashRecovery
}
