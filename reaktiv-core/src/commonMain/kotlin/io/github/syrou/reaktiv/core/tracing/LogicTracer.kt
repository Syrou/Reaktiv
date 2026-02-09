package io.github.syrou.reaktiv.core.tracing

import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Central registry for logic method tracing.
 *
 * The compiler plugin injects calls to this object's notify methods at the start and end
 * of traced ModuleLogic methods. Observers registered here receive events for all traced
 * method invocations across the application.
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
object LogicTracer {

    private val observers = mutableListOf<LogicObserver>()
    private var callIdCounter = 0L

    /**
     * Registers an observer to receive logic method tracing events.
     *
     * @param observer The observer to register
     */
    fun addObserver(observer: LogicObserver) {
        if (observer !in observers) {
            observers.add(observer)
            println("LogicTracer: Observer added, total observers: ${observers.size}")
        }
    }

    /**
     * Removes a previously registered observer.
     *
     * @param observer The observer to remove
     * @return true if the observer was removed, false if it wasn't registered
     */
    fun removeObserver(observer: LogicObserver): Boolean {
        return observers.remove(observer)
    }

    /**
     * Removes all registered observers.
     */
    fun clearObservers() {
        observers.clear()
    }

    /**
     * Returns the current number of registered observers.
     */
    fun observerCount(): Int = observers.size

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
    private val callIdToMethod = mutableMapOf<String, String>()

    fun notifyMethodStart(
        logicClass: String,
        methodName: String,
        params: Map<String, String>,
        sourceFile: String? = null,
        lineNumber: Int? = null,
        githubSourceUrl: String? = null
    ): String {
        val callId = generateCallId()
        val fullMethodName = "$logicClass.$methodName"
        callIdToMethod[callId] = fullMethodName
        println("LogicTracer: notifyMethodStart called - $fullMethodName [callId=$callId] (observers: ${observers.size})")
        val timestampMs = currentTimeMillis()

        val event = LogicMethodStart(
            logicClass = logicClass,
            methodName = methodName,
            params = params,
            callId = callId,
            timestampMs = timestampMs,
            sourceFile = sourceFile,
            lineNumber = lineNumber,
            githubSourceUrl = githubSourceUrl
        )

        notifyObservers { it.onMethodStart(event) }

        return callId
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
    fun notifyMethodCompleted(
        callId: String,
        result: String?,
        resultType: String,
        durationMs: Long
    ) {
        val methodName = callIdToMethod.remove(callId) ?: "unknown"
        println("LogicTracer: notifyMethodCompleted called - $methodName [callId=$callId] resultType=$resultType, durationMs=$durationMs (observers: ${observers.size})")
        val event = LogicMethodCompleted(
            callId = callId,
            result = result,
            resultType = resultType,
            durationMs = durationMs
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
    fun notifyMethodFailed(
        callId: String,
        exception: Throwable,
        durationMs: Long
    ) {
        val methodName = callIdToMethod.remove(callId) ?: "unknown"
        println("LogicTracer: notifyMethodFailed called - $methodName [callId=$callId] exception=${exception::class.simpleName}: ${exception.message}, durationMs=$durationMs (observers: ${observers.size})")
        val event = LogicMethodFailed(
            callId = callId,
            exceptionType = exception::class.simpleName ?: "Unknown",
            exceptionMessage = exception.message,
            stackTrace = exception.stackTraceToString(),
            durationMs = durationMs
        )

        notifyObservers { it.onMethodFailed(event) }
    }

    private fun generateCallId(): String {
        val id = ++callIdCounter
        return "call-$id-${currentTimeMillis()}"
    }

    private inline fun notifyObservers(action: (LogicObserver) -> Unit) {
        // Take a snapshot to safely iterate while allowing concurrent modifications
        for (observer in observers.toList()) {
            try {
                action(observer)
            } catch (e: Throwable) {
                println("LogicTracer: Observer threw exception: ${e.message}")
            }
        }
    }
}

/**
 * Gets current time in milliseconds using Kotlin's Clock.System.
 */
@OptIn(ExperimentalTime::class)
internal fun currentTimeMillis(): Long = Clock.System.now().toEpochMilliseconds()
