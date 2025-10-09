package io.github.syrou.reaktiv.core

import kotlinx.coroutines.flow.StateFlow
import kotlin.native.ObjCName
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
@ObjCName("StateSelector")
open class StateSelector<S : ModuleState> @PublishedApi internal constructor(
    @PublishedApi internal val stateClass: KClass<S>
) {
    /**
     * Select state from store (suspending).
     */
    suspend fun select(store: Store): StateFlow<S> {
        return store.selectState(stateClass)
    }

    /**
     * Select state from store (non-suspending).
     */
    fun selectNow(store: Store): StateFlow<S> {
        return store.selectStateNonSuspend(stateClass)
    }

    /**
     * Get the current state value.
     */
    fun getValue(store: Store): S {
        return store.selectStateNonSuspend(stateClass).value
    }

    companion object {
        /**
         * Create a selector for a specific state type.
         */
        inline fun <reified S : ModuleState> create(): StateSelector<S> {
            return StateSelector(S::class)
        }
    }
}