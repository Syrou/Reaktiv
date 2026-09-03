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
     *         [DispatchResult.Blocked] if middleware blocked the action or a reset dropped it,
     *         [DispatchResult.Error] if processing failed
     * @throws IllegalStateException when called from inside the dispatch pipeline, which would wait
     *         for itself. Middleware and [ModuleLogic.beforeReset] must use [dispatch] instead.
     */
    public abstract suspend fun dispatchAndAwait(action: ModuleAction): DispatchResult

    /**
     * Resets every module to its initial state and recreates every logic instance.
     *
     * A reset runs in order. Every coroutine the store launched for the current generation is
     * cancelled and joined, bounded by a timeout after which a warning is logged and the reset
     * continues without the stragglers. The dispatch pipeline then, in one ordered step, calls
     * [ModuleLogic.beforeReset] on each logic instance, swaps state and logic, and drops every
     * action that was queued before the reset with [DispatchResult.Blocked].
     *
     * The coroutine that awaits this call survives the reset, so a logic method can continue
     * afterwards against the fresh store. Every other coroutine of the old generation is
     * cancelled. Calling this from inside middleware throws, because the pipeline that would
     * complete the reset is busy running that middleware. Use [resetAsync] there.
     *
     * Only one reset can execute at a time. A call made while another reset is in progress
     * returns false immediately. An exception thrown by [ModuleLogic.beforeReset] is rethrown
     * once the reset has completed.
     *
     * Example:
     * ```kotlin
     * suspend fun logout() {
     *     storeAccessor.dispatch(AuthAction.LoggingOut(true))
     *     api.logout()
     *     storeAccessor.reset()
     * }
     * ```
     *
     * @return true if the reset ran, false if one was already in progress
     * @throws IllegalArgumentException if the store has not been constructed yet
     * @throws IllegalStateException if called from inside the dispatch pipeline
     */
    public abstract suspend fun reset(): Boolean

    /**
     * Launches [reset] on the store's root job and returns immediately.
     *
     * The returned job belongs to no generation, so the reset it runs cannot cancel it. Use this
     * from middleware, or anywhere that cannot suspend on the reset itself.
     *
     * @return A [Job] that completes when the reset finishes
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
    /**
     * A snapshot of every registered module's current state, keyed by the state class's
     * qualified name.
     *
     * Each module owns its own state flow, so the snapshot is a read of each in turn rather
     * than a transactional view of the whole tree.
     */
    public abstract fun getAllStates(): Map<String, ModuleState>

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
