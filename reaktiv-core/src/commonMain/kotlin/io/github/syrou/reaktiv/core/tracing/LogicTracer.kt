package io.github.syrou.reaktiv.core.tracing

import io.github.syrou.reaktiv.core.util.ReaktivDebug
import io.github.syrou.reaktiv.core.util.currentTimeMillis
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * Central registry for logic method tracing.
 *
 * The compiler plugin injects calls to this object's notify methods at the start and end
 * of traced ModuleLogic methods. Observers registered here receive events for all traced
 * method invocations across the application.
 *
 * When no observers are registered, all notify methods return immediately without
 * allocating events, so inactive tracing has near-zero runtime cost.
 *
 * Usage:
 * ```kotlin
 * // Register an observer to receive tracing events
 * val observer = object : LogicObserver {
 *     override fun onMethodStart(event: LogicMethodStart) {
 *         println("Started: ${event.logicClass}.${event.methodName}")
 *     }
 *     override fun onMethodCompleted(event: LogicMethodCompleted) {
 *         println("Completed: ${event.callId} in ${event.durationMs}ms")
 *     }
 *     override fun onMethodFailed(event: LogicMethodFailed) {
 *         println("Failed: ${event.callId} - ${event.exceptionType}")
 *     }
 * }
 *
 * LogicTracer.addObserver(observer)
 *
 * // Later, when done:
 * LogicTracer.removeObserver(observer)
 * ```
 *
 * @see LogicObserver for the observer interface
 * @see LogicMethodStart for method start events
 * @see LogicMethodCompleted for method completion events
 * @see LogicMethodFailed for method failure events
 */
@OptIn(ExperimentalAtomicApi::class)
public object LogicTracer {

    private val observers = AtomicReference<List<LogicObserver>>(emptyList())
    private val callIdCounter = AtomicLong(0L)

    /**
     * Whether any observer is registered. Compiler-injected code checks this before
     * building parameter and result strings so inactive tracing costs nothing.
     */
    public val active: Boolean get() = observers.load().isNotEmpty()

    /**
     * Registers an observer to receive logic method tracing events.
     *
     * @param observer The observer to register
     */
    public fun addObserver(observer: LogicObserver) {
        while (true) {
            val current = observers.load()
            if (observer in current) return
            if (observers.compareAndSet(current, current + observer)) return
        }
    }

    /**
     * Removes a previously registered observer.
     *
     * @param observer The observer to remove
     * @return true if the observer was removed, false if it wasn't registered
     */
    public fun removeObserver(observer: LogicObserver): Boolean {
        while (true) {
            val current = observers.load()
            if (observer !in current) return false
            if (observers.compareAndSet(current, current - observer)) return true
        }
    }

    /**
     * Removes all registered observers.
     */
    public fun clearObservers() {
        observers.store(emptyList())
    }

    /**
     * Returns the current number of registered observers.
     */
    public fun observerCount(): Int = observers.load().size

    /**
     * Notifies observers that a traced method has started.
     *
     * Called by compiler-injected code at the start of traced methods.
     *
     * @param logicClass Fully qualified name of the Logic class
     * @param methodName Name of the method being executed
     * @param params Map of parameter names to their string representations
     * @param sourceFile Source file path relative to project root (optional, for IDE navigation)
     * @param lineNumber Line number (optional, for IDE navigation)
     * @param githubSourceUrl Full GitHub URL to the source line (built at compile time)
     * @return A unique call ID for correlating with completion/failure notifications
     */
    public fun notifyMethodStart(
        logicClass: String,
        methodName: String,
        params: Map<String, String>,
        sourceFile: String? = null,
        lineNumber: Int? = null,
        githubSourceUrl: String? = null
    ): String {
        if (!active) return ""
        val timestampMs = currentTimeMillis()
        val event = LogicMethodStart(
            logicClass = logicClass,
            methodName = methodName,
            params = params,
            callId = "call-${callIdCounter.addAndFetch(1L)}-$timestampMs",
            timestampMs = timestampMs,
            sourceFile = sourceFile,
            lineNumber = lineNumber,
            githubSourceUrl = githubSourceUrl
        )
        notifyObservers { it.onMethodStart(event) }
        return event.callId
    }

    /**
     * Notifies observers that a traced method completed successfully.
     *
     * Called by compiler-injected code when a method returns normally.
     *
     * @param callId The call ID returned by [notifyMethodStart]
     * @param result String representation of the return value
     * @param resultType Simple name of the result type
     * @param durationMs Time in milliseconds from method start
     */
    public fun notifyMethodCompleted(
        callId: String,
        result: String?,
        resultType: String,
        durationMs: Long
    ) {
        if (!active) return
        val event = LogicMethodCompleted(
            callId = callId,
            result = result,
            resultType = resultType,
            durationMs = durationMs,
            timestampMs = currentTimeMillis()
        )
        notifyObservers { it.onMethodCompleted(event) }
    }

    /**
     * Notifies observers that a traced method failed with an exception.
     *
     * Called by compiler-injected code when a method throws an exception.
     *
     * @param callId The call ID returned by [notifyMethodStart]
     * @param exception The thrown exception
     * @param durationMs Time in milliseconds from method start
     */
    public fun notifyMethodFailed(
        callId: String,
        exception: Throwable,
        durationMs: Long
    ) {
        if (!active) return
        val event = LogicMethodFailed(
            callId = callId,
            exceptionType = exception::class.simpleName ?: "Unknown",
            exceptionMessage = exception.message,
            stackTrace = exception.stackTraceToString(),
            durationMs = durationMs,
            timestampMs = currentTimeMillis()
        )
        notifyObservers { it.onMethodFailed(event) }
    }

    private inline fun notifyObservers(action: (LogicObserver) -> Unit) {
        for (observer in observers.load()) {
            try {
                action(observer)
            } catch (e: Throwable) {
                ReaktivDebug.warn("LogicTracer observer threw exception: ${e.message}")
            }
        }
    }
}
