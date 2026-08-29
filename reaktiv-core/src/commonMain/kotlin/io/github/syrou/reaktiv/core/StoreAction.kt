package io.github.syrou.reaktiv.core

/**
 * Actions the [Store] itself reduces, rather than routing to a module.
 *
 * A module action names the module that handles it and is reduced by that module's reducer.
 * A store action operates on the whole state tree, so the store applies it directly.
 *
 * Store actions travel the same ordered dispatch pipeline as every other action, so they are
 * serialised against module dispatches rather than racing them. They do not pass through the
 * middleware chain: [Middleware] is defined in terms of one module's resulting state, and a
 * store action has no single module. A consequence worth relying on is that a store action
 * triggers no middleware-driven work, which is why a projection fires no navigation lifecycle
 * hooks.
 */
public sealed class StoreAction : ModuleAction(StoreAction::class), HighPriorityAction, ExternalControlExempt {

    /**
     * Replaces the state of the named modules.
     *
     * This is how state arrives from outside the store: a remote publisher projecting onto a
     * follower, or persisted state being restored. It carries state rather than intent, so no
     * module logic runs and no side effect is repeated. Re-dispatching the original actions
     * would do both, which is why replication projects state instead.
     *
     * Entries naming an unregistered module, or carrying a state of the wrong type for the
     * module they name, are skipped with a warning rather than failing the dispatch.
     *
     * Usage:
     * ```kotlin
     * store.dispatchAndAwait(
     *     StoreAction.Hydrate(
     *         states = mapOf("com.example.CounterState" to CounterState(value = 42)),
     *         origin = "DevTools"
     *     )
     * )
     * ```
     *
     * @param states New state per module, keyed by the state class's qualified name.
     * @param origin Short label naming what produced this state, used in diagnostics.
     */
    public data class Hydrate(
        val states: Map<String, ModuleState>,
        val origin: String
    ) : StoreAction()
}
