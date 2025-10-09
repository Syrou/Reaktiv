package io.github.syrou.reaktiv.core

import kotlinx.coroutines.flow.StateFlow
import kotlin.reflect.KClass

/**
 * Generic state selector for Swift/iOS.
 * Wraps KClass internally so Swift doesn't need to deal with it.
 *
 * Example from Swift:
 * ```swift
 * let selector = CounterModule.shared.stateSelector
 * let stateFlow = await selector.select(store: store)
 * 
 * for await state in stateFlow {
 *     print("Count: \(state.count)")
 * }
 * ```
 */
class StateSelector<S : ModuleState>(
    private val stateClass: KClass<S>
) {
    /**
     * Select state from store (suspending).
     * SKIE converts this to an async function returning AsyncSequence in Swift.
     * 
     * @param store The store to select state from
     * @return StateFlow of the state
     */
    suspend fun select(store: Store): StateFlow<S> {
        return store.selectState(stateClass)
    }
    
    /**
     * Select state from store (non-suspending).
     * Will throw if the store isn't initialized yet.
     * 
     * @param store The store to select state from
     * @return StateFlow of the state
     */
    fun selectNow(store: Store): StateFlow<S> {
        return store.selectStateNonSuspend(stateClass)
    }
    
    /**
     * Get the current state value (non-suspending, non-reactive).
     * Will throw if the store isn't initialized yet.
     * 
     * @param store The store to get state from
     * @return Current state value
     */
    fun getValue(store: Store): S {
        return store.selectStateNonSuspend(stateClass).value
    }
    
    companion object {
        /**
         * Create a selector for a specific state type.
         * This is internal - call from Kotlin side only using reified types.
         */
        inline fun <reified S : ModuleState> create(): StateSelector<S> {
            return StateSelector(S::class)
        }
    }
}