package io.github.syrou.reaktiv.core

/**
 * Internal operations for specialized use cases like DevTools and testing.
 *
 * This interface provides low-level access to store internals that bypasses
 * the normal MVLI action/reducer/logic pipeline. It should ONLY be used for:
 *
 * - DevTools state synchronization from remote sources
 * - Test fixtures and state setup
 * - State restoration from external systems
 *
 * Using this in normal application logic defeats the purpose of the MVLI
 * architecture and should be avoided.
 */
@ExperimentalReaktivApi
public interface InternalStoreOperations {
    /**
     * Replaces the state of the named modules without running their reducers.
     *
     * Delegates to [StoreAction.Hydrate], which the store applies from its dispatch loop, so
     * the update is ordered against every other dispatch rather than written alongside them.
     *
     * Each module's state is written to its own [kotlinx.coroutines.flow.MutableStateFlow],
     * so a single module's update is atomic, but the map is not applied as one
     * transaction: a collector of one module can observe its new value before another
     * module in the same call has been written.
     *
     * Example usage:
     * ```kotlin
     * storeAccessor.dispatchAndAwait(
     *     StoreAction.Hydrate(
     *         states = mapOf("com.example.CounterState" to CounterState(value = 42)),
     *         origin = "DevTools"
     *     )
     * )
     * ```
     *
     * @param states Map of state class qualified names to new state instances
     */
    @Deprecated(
        "Dispatch StoreAction.Hydrate instead, so external state is ordered against every " +
            "other dispatch rather than written alongside the pipeline.",
        ReplaceWith("dispatchAndAwait(StoreAction.Hydrate(states, \"External\"))"),
        DeprecationLevel.WARNING
    )
    public suspend fun applyExternalStates(states: Map<String, ModuleState>)

    /**
     * Puts the store under external control, making a remote publisher the author of its state.
     *
     * Every subsequently dispatched action that is not [ExternalControlExempt] is dropped and
     * reported as [DispatchResult.Blocked], so the only way state changes is
     * [applyExternalStates]. Each [ModuleLogic] is notified through
     * [ModuleLogic.onExternalControlChanged] before the gate engages, giving logic a chance to
     * settle in-flight work while dispatch still works.
     *
     * Must not be called from within action processing. The notification hook dispatches, and
     * the dispatch loop is a single consumer, so calling this from a middleware or a reducer
     * deadlocks.
     *
     * Example usage:
     * ```kotlin
     * storeAccessor.asInternalOperations()?.beginExternalControl()
     * ```
     */
    public suspend fun beginExternalControl()

    /**
     * Engages the dispatch gate synchronously, before any logic has had a chance to run.
     *
     * Tooling uses this when a client is configured to start as a follower, so that start-up
     * work is never begun rather than begun and then cancelled. Safe to call from a
     * [Module.createLogic] constructor: no hooks are notified, because no logic can have
     * in-flight work at that point.
     *
     * Prefer [beginExternalControl] once the store is running.
     */
    public fun markExternallyDriven()

    /**
     * Returns the store to local control, re-enabling the normal dispatch pipeline.
     *
     * State projected while under external control is left in place, so callers that want a
     * clean local start should follow this with [Store.reset].
     */
    public suspend fun endExternalControl()
}
