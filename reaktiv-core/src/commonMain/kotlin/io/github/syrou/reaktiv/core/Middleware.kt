package io.github.syrou.reaktiv.core

/**
 * Type alias for middleware functions.
 *
 * Middleware allows you to intercept actions and perform additional operations
 * like logging, analytics, or side effects before and after the action is processed.
 *
 * Example:
 * ```kotlin
 * val loggingMiddleware: Middleware = { action, getAllStates, storeAccessor, updatedState ->
 *     println("Before: $action")
 *     val newState = updatedState(action)
 *     println("After: $newState")
 * }
 *
 * val store = createStore {
 *     module(MyModule)
 *     middlewares(loggingMiddleware)
 * }
 * ```
 *
 * @param action The action being dispatched
 * @param getAllStates Function to get all current module states
 * @param storeAccessor Access to dispatch, state selection, and logic selection
 * @param updatedState Function to continue processing the action and get the resulting state
 */
public fun interface Middleware {
    public suspend operator fun invoke(
        action: ModuleAction,
        getAllStates: suspend () -> Map<String, ModuleState>,
        storeAccessor: StoreAccessor,
        updatedState: suspend (ModuleAction) -> ModuleState,
    )
}
