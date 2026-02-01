package io.github.syrou.reaktiv.core.util

import io.github.syrou.reaktiv.core.ModuleAction
import io.github.syrou.reaktiv.core.ModuleLogic
import io.github.syrou.reaktiv.core.ModuleState
import io.github.syrou.reaktiv.core.StoreAccessor
import kotlinx.coroutines.flow.StateFlow

/**
 * Selects a module's state flow by its type.
 *
 * This is the primary way to observe module state from within Logic classes
 * or other components with access to a StoreAccessor.
 *
 * Example:
 * ```kotlin
 * class MyLogic(private val storeAccessor: StoreAccessor) : ModuleLogic<MyAction>() {
 *     suspend fun checkUserState() {
 *         val userState = storeAccessor.selectState<UserState>().first()
 *         if (userState.isLoggedIn) {
 *             // ...
 *         }
 *     }
 * }
 * ```
 *
 * @return StateFlow of the requested state type
 * @throws IllegalStateException if no module with the specified state type is registered
 */
suspend inline fun <reified S : ModuleState> StoreAccessor.selectState(): StateFlow<S> = this.selectState(S::class)

/**
 * Selects a module's logic instance by its type.
 *
 * Use this to access another module's logic for cross-module operations.
 *
 * Example:
 * ```kotlin
 * class OrderLogic(private val storeAccessor: StoreAccessor) : ModuleLogic<OrderAction>() {
 *     suspend fun processOrder() {
 *         val paymentLogic = storeAccessor.selectLogic<PaymentLogic>()
 *         paymentLogic.processPayment(amount)
 *     }
 * }
 * ```
 *
 * @return The logic instance of the requested type
 * @throws IllegalStateException if no module with the specified logic type is registered
 */
suspend inline fun <reified L : ModuleLogic<out ModuleAction>> StoreAccessor.selectLogic(): L = selectLogic(L::class)