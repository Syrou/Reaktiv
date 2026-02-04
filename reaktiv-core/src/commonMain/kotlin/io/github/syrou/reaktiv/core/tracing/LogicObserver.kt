package io.github.syrou.reaktiv.core.tracing

/**
 * Observer interface for receiving logic method tracing events.
 *
 * Implement this interface and register with [LogicTracer] to receive
 * notifications when traced ModuleLogic methods start, complete, or fail.
 *
 * Usage:
 * ```kotlin
 * class MyTracingObserver : LogicObserver {
 *     override fun onMethodStart(event: LogicMethodStart) {
 *         println("Method started: ${event.logicClass}.${event.methodName}")
 *     }
 *
 *     override fun onMethodCompleted(event: LogicMethodCompleted) {
 *         println("Method completed in ${event.durationMs}ms")
 *     }
 *
 *     override fun onMethodFailed(event: LogicMethodFailed) {
 *         println("Method failed: ${event.exceptionType}")
 *     }
 * }
 *
 * // Register the observer
 * LogicTracer.addObserver(MyTracingObserver())
 * ```
 *
 * @see LogicTracer for managing observers
 * @see LogicMethodStart for start event details
 * @see LogicMethodCompleted for completion event details
 * @see LogicMethodFailed for failure event details
 */
interface LogicObserver {
    /**
     * Called when a traced logic method begins execution.
     *
     * @param event Details about the method invocation
     */
    fun onMethodStart(event: LogicMethodStart)

    /**
     * Called when a traced logic method completes successfully.
     *
     * @param event Details about the method completion
     */
    fun onMethodCompleted(event: LogicMethodCompleted)

    /**
     * Called when a traced logic method throws an exception.
     *
     * @param event Details about the method failure
     */
    fun onMethodFailed(event: LogicMethodFailed)
}
