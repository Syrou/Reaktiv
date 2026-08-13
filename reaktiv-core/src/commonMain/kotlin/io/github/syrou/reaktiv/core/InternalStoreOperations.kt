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
     * Applies external state updates directly to the store, bypassing the
     * action/reducer/logic pipeline.
     *
     * This method updates the store's state without dispatching actions or
     * executing reducers. The states are applied atomically within the store's
     * internal mutex lock to ensure thread safety.
     *
     * Example usage:
     * ```kotlin
     * val internalOps = storeAccessor.asInternalOperations()
     * internalOps?.applyExternalStates(mapOf(
     *     "com.example.CounterState" to CounterState(value = 42),
     *     "com.example.UserState" to UserState(name = "Alice")
     * ))
     * ```
     *
     * @param states Map of state class qualified names to new state instances
     */
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
