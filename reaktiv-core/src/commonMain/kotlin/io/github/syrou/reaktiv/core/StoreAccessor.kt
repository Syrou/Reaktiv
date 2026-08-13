package io.github.syrou.reaktiv.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlin.coroutines.CoroutineContext
import kotlin.reflect.KClass

/**
 * Provides access to dispatch, state selection, and logic selection.
 *
 * StoreAccessor is passed to Logic classes during construction, allowing them
 * to dispatch actions, read state from other modules, and access other Logic instances.
 *
 * Example:
 * ```kotlin
 * class OrderLogic(private val storeAccessor: StoreAccessor) : ModuleLogic() {
 *     suspend fun placeOrder(order: Order) {
 *         // Dispatch an action
 *         storeAccessor.dispatch(OrderAction.SetProcessing(true))
 *
 *         // Read state from another module
 *         val userState = storeAccessor.selectState<UserState>().first()
 *
 *         // Access another module's logic
 *         val paymentLogic = storeAccessor.selectLogic<PaymentLogic>()
 *         paymentLogic.processPayment(order.total)
 *     }
 * }
 * ```
 */
public abstract class StoreAccessor(scope: CoroutineScope) : CoroutineScope {
    override val coroutineContext: CoroutineContext = scope.coroutineContext

    /**
     * Select a module's state flow by its class.
     *
     * @param stateClass The KClass of the state to select
     * @return StateFlow of the requested state type
     */
    public abstract suspend fun <S : ModuleState> selectState(stateClass: KClass<S>): StateFlow<S>

    /**
     * Select a module's logic instance by its class.
     *
     * @param logicClass The KClass of the logic to select
     * @return The logic instance
     */
    public abstract suspend fun <L : ModuleLogic> selectLogic(logicClass: KClass<L>): L

    /**
     * The dispatch function for sending actions to the store.
     * This is fire-and-forget - it returns immediately without waiting for processing.
     */
    public abstract val dispatch: Dispatch

    /**
     * Dispatch an action and wait for it to be processed.
     * Returns a [DispatchResult] indicating whether the action was applied or blocked.
     *
     * Use this when you need to know the outcome of the dispatch, for example:
     * - When middleware might block the action (spam protection)
     * - When you need to perform follow-up work only if the action was applied
     *
     * @param action The action to dispatch
     * @return [DispatchResult.Processed] if action was applied,
     *         [DispatchResult.Blocked] if middleware blocked the action,
     *         [DispatchResult.Error] if processing failed
     */
    public abstract suspend fun dispatchAndAwait(action: ModuleAction): DispatchResult

    /**
     * Resets the store by cancelling all child coroutines and restarting action processing.
     *
     * Only one reset can execute at a time. If a reset is already in progress, this function
     * returns false immediately without waiting or executing.
     *
     * All module logic instances will have their [ModuleLogic.onStoreReset] method called
     * sequentially. Any exceptions thrown during reset will propagate to the caller.
     *
     * Safe to call from action handlers - uses [NonCancellable] context to ensure
     * reset completes even if called from within the store's own action processing.
     *
     * For fire-and-forget usage, use [resetAsync] instead.
     *
     * @return true if reset was executed, false if skipped due to concurrent reset
     * @throws IllegalArgumentException if the store is not initialized
     */
    public abstract suspend fun reset(): Boolean

    /**
     * Non-suspend convenience function that resets the store asynchronously.
     *
     * This launches [reset] in the store's coroutine scope and returns immediately.
     * Use this for fire-and-forget reset operations where you don't need to wait
     * for completion.
     *
     * If you need to wait for reset to complete, use the suspend [reset] function instead.
     *
     * @return A [Job] that completes when the reset finishes.
     */
    public abstract fun resetAsync(): Job

    /**
     * Provides access to internal store operations for specialized use cases.
     *
     * This method returns an [InternalStoreOperations] instance if the store
     * supports it, allowing access to low-level operations like external state
     * application.
     *
     * This is intentionally not a direct property to make misuse less discoverable.
     * Requires [ExperimentalReaktivApi] opt-in.
     *
     * @return InternalStoreOperations instance or null if not supported
     */
    @ExperimentalReaktivApi
    public fun asInternalOperations(): InternalStoreOperations? = this as? InternalStoreOperations

    /**
     * Get a module instance by its class.
     *
     * Use this when the module type is only known at runtime (e.g. dynamic plugin systems,
     * middleware, debug tooling). When the type is known at compile time, prefer the reified
     * overload [getModule] or [getRegisteredModules] for Swift/Obj-C interop.
     *
     * Example usage:
     * ```kotlin
     * val navModule = storeAccessor.getModule(NavigationModule::class)
     *     ?: error("NavigationModule not registered")
     * ```
     *
     * @param moduleClass The [KClass] of the module to retrieve
     * @return The module instance if registered, null otherwise
     * @see getModule reified overload for compile-time known types
     * @see getRegisteredModules for Swift/Obj-C interop
     */
    public abstract fun <M : Any> getModule(moduleClass: KClass<M>): M?

    /**
     * Convenience reified overload for [getModule].
     *
     * Preferred way to retrieve a module from Kotlin when the type is known at compile time.
     *
     * Example usage:
     * ```kotlin
     * val navModule = storeAccessor.getModule<NavigationModule>()
     *     ?: error("NavigationModule not registered")
     * ```
     *
     * @return The module instance if registered, null otherwise
     * @see getRegisteredModules for Swift/Obj-C interop
     */
    public inline fun <reified M : Any> getModule(): M? = getModule(M::class)

    /**
     * Returns all modules registered in this store.
     *
     * This is the Swift/Obj-C-friendly way to retrieve a specific module instance.
     * Swift cannot construct a [KClass] to pass to [getModule], so this method allows
     * Swift to use its own type system instead:
     *
     * ```swift
     * let navModule = store.getRegisteredModules()
     *     .first { $0 is NavigationModule } as? NavigationModule
     * ```
     *
     * The recommended primary approach for Swift interop is to expose module instances
     * as typed properties on your SDK class, and use this method only as a fallback
     * when a direct reference is not available.
     *
     * From Kotlin, prefer [getModule] with a reified type parameter instead.
     *
     * @return Snapshot list of all registered module instances
     * @see getModule for Kotlin callers
     */
    public abstract fun getRegisteredModules(): List<Module<*, *>>

    /**
     * Returns the [StateFlow] for the given module's state, or null if the module
     * is not registered in this store.
     *
     * This is a non-suspend, direct accessor intended for use by module interface
     * default implementations (e.g. [Module.selectStateFlowNonSuspend]) and for
     * Swift/SKIE interop where suspend functions cannot be called.
     *
     * @param module The module whose state flow to retrieve
     * @return The [StateFlow] of the module's state, or null if not registered
     */
    public abstract fun getStateFlowForModule(module: Module<*, *>): StateFlow<ModuleState>?

    /**
     * Returns the logic instance for the given module, suspending until the store
     * is fully initialized.
     *
     * @param module The module whose logic to retrieve
     * @return The logic instance, or null if the module is not registered
     */
    public abstract suspend fun getLogicForModule(module: Module<*, *>): ModuleLogic?

    /**
     * Registers a [CrashListener] to be notified when logic invocation throws.
     */
    @ExperimentalReaktivApi
    public abstract fun addCrashListener(listener: CrashListener)

    /**
     * Removes a previously registered [CrashListener].
     */
    @ExperimentalReaktivApi
    public abstract fun removeCrashListener(listener: CrashListener)

}
