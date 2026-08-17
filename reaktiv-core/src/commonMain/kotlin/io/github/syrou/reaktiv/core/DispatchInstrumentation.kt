package io.github.syrou.reaktiv.core

/**
 * Wraps a single step of the dispatch chain so instrumentation can measure it.
 *
 * A decorator is handed each named step, a middleware or the reducer, and returns a replacement
 * that does whatever timing or bookkeeping it needs around the original.
 *
 * Usage:
 * ```kotlin
 * val decorator = DispatchStepDecorator { name, step ->
 *     { action ->
 *         val start = currentTimeMillis()
 *         try {
 *             step(action)
 *         } finally {
 *             println("$name took ${currentTimeMillis() - start}ms")
 *         }
 *     }
 * }
 * ```
 *
 * @see DispatchInstrumentation.newDispatchDecorator
 */
public fun interface DispatchStepDecorator {

    public fun decorate(
        name: String,
        step: suspend (ModuleAction) -> Unit
    ): suspend (ModuleAction) -> Unit
}

/**
 * The seam through which tooling observes a [Store] without core depending on any tooling artifact.
 *
 * Core emits neutral events here and holds no tracer, no timing and no event types of its own.
 * Implementations live outside core, map these callbacks onto whatever they record, and are
 * installed with [Store.setDispatchInstrumentation]. A store with nothing installed pays one null
 * check per dispatch.
 *
 * Two families of callbacks share the interface. The `onDispatch*` methods cover work inside the
 * dispatch pipeline and are driven by the store itself. The `onEvaluation*` methods cover suspending
 * work that happens outside the pipeline and still deserves a span, such as navigation guards and
 * dynamic entry selectors, and are driven by whichever module performs that work after reading
 * [Store.activeDispatchInstrumentation].
 *
 * Usage:
 * ```kotlin
 * class LoggingInstrumentation : DispatchInstrumentation {
 *
 *     override suspend fun onDispatchStarted(
 *         action: ModuleAction,
 *         queueWaitMs: Long,
 *         queueDepth: Long
 *     ): String {
 *         println("dispatch ${action::class.simpleName} waited ${queueWaitMs}ms")
 *         return action.toString()
 *     }
 *
 *     override fun onDispatchCompleted(token: String, applied: Boolean, durationMs: Long) {
 *         println("$token ${if (applied) "applied" else "blocked"} in ${durationMs}ms")
 *     }
 *
 *     override fun onDispatchFailed(token: String, error: Throwable, durationMs: Long) {
 *         println("$token failed: ${error.message}")
 *     }
 *
 *     override suspend fun onDispatchDropped(action: ModuleAction) = Unit
 *
 *     override suspend fun onExternalControlChanged(enabled: Boolean) = Unit
 * }
 *
 * store.setDispatchInstrumentation(LoggingInstrumentation())
 * ```
 *
 * @see Store.setDispatchInstrumentation to install an implementation
 * @see Store.activeDispatchInstrumentation to read the installed one
 */
public interface DispatchInstrumentation {

    /**
     * Whether this instrumentation currently wants events.
     *
     * The store skips all bookkeeping when this is false, and
     * [Store.activeDispatchInstrumentation] reports null, so an implementation backed by a listener
     * registry can return false while nothing is listening and cost callers nothing.
     */
    public val isActive: Boolean get() = true

    /**
     * Called when the store begins processing an action.
     *
     * @param action The action leaving the queue
     * @param queueWaitMs Time the action spent queued before processing began
     * @param queueDepth Number of actions still enqueued behind this one
     * @return A token correlating this dispatch with its completion or failure
     */
    public suspend fun onDispatchStarted(
        action: ModuleAction,
        queueWaitMs: Long,
        queueDepth: Long
    ): String

    /**
     * Called when a dispatch finishes, whether or not it changed state.
     *
     * @param token The token returned by [onDispatchStarted]
     * @param applied `false` when the action was blocked rather than processed
     * @param durationMs Time from dispatch start
     */
    public fun onDispatchCompleted(token: String, applied: Boolean, durationMs: Long)

    /**
     * Called when a dispatch throws.
     *
     * @param token The token returned by [onDispatchStarted]
     * @param error The thrown exception
     * @param durationMs Time from dispatch start
     */
    public fun onDispatchFailed(token: String, error: Throwable, durationMs: Long)

    /**
     * Called when an action is discarded without processing, which happens while the store is
     * externally driven and the action is not [ExternalControlExempt].
     *
     * @param action The discarded action
     */
    public suspend fun onDispatchDropped(action: ModuleAction)

    /**
     * Called when a remote publisher takes over authoring this store's state, or hands it back.
     *
     * @param enabled `true` when external control begins, `false` when it ends
     */
    public suspend fun onExternalControlChanged(enabled: Boolean)

    /**
     * Supplies a decorator for wrapping individual dispatch chain steps, or `null` to leave the
     * chain untouched.
     *
     * Called once per dispatch, so the returned decorator may hold per-dispatch state.
     */
    public fun newDispatchDecorator(): DispatchStepDecorator? = null

    /**
     * Called when a suspending evaluation outside the dispatch pipeline begins.
     *
     * Navigation uses this for guards and dynamic entry selectors, which run during navigation
     * rather than during a dispatch and would otherwise be invisible.
     *
     * Returning an empty string declines to trace this evaluation, and callers must tolerate it by
     * treating the empty token as "no span", which is what the default implementation relies on.
     *
     * @param scope Grouping label for the evaluation, such as `"NavigationGuards"`
     * @param name Name of the specific evaluation, such as `"guard(workspace)"`
     * @param params Contextual values describing the evaluation
     * @return A token correlating this evaluation with its completion or failure
     */
    public suspend fun onEvaluationStarted(
        scope: String,
        name: String,
        params: Map<String, String>
    ): String = ""

    /**
     * Called when an evaluation returns normally.
     *
     * @param token The token returned by [onEvaluationStarted]
     * @param result String representation of the outcome, such as `"RedirectTo(login)"`
     * @param resultType Simple name of the result type, such as `"GuardResult"`
     * @param durationMs Time from evaluation start
     */
    public fun onEvaluationCompleted(
        token: String,
        result: String?,
        resultType: String,
        durationMs: Long
    ) {
    }

    /**
     * Called when an evaluation throws.
     *
     * @param token The token returned by [onEvaluationStarted]
     * @param error The thrown exception
     * @param durationMs Time from evaluation start
     */
    public fun onEvaluationFailed(token: String, error: Throwable, durationMs: Long) {
    }
}
