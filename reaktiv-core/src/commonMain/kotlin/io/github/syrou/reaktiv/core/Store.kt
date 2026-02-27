@file:OptIn(ExperimentalCoroutinesApi::class)

package io.github.syrou.reaktiv.core

import io.github.syrou.reaktiv.core.persistance.PersistenceManager
import io.github.syrou.reaktiv.core.persistance.PersistenceStrategy
import io.github.syrou.reaktiv.core.util.CustomTypeRegistrar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlinx.coroutines.yield
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.PolymorphicModuleBuilder
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.serializer
import kotlin.coroutines.CoroutineContext
import kotlin.reflect.KClass


@RequiresOptIn(
    message = "This API is for specialized DevTools and testing use only. " +
            "Using it in application code bypasses MVLI patterns and is strongly discouraged.",
    level = RequiresOptIn.Level.WARNING
)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
annotation class ExperimentalReaktivApi

/**
 * Determines how the Store should handle a crash after listeners are notified.
 */
enum class CrashRecovery {
    /**
     * Navigate to crash screen and do NOT re-throw.
     * The developer is responsible for reporting to Crashlytics via recordException().
     */
    NAVIGATE_TO_CRASH_SCREEN,

    /**
     * Let the crash propagate normally. Default behavior.
     */
    RETHROW
}

/**
 * Listener for crashes that occur during logic execution in the Store.
 *
 * Implementations can handle crash recovery (e.g., navigating to a crash screen)
 * and return a [CrashRecovery] to control whether the exception is re-thrown.
 *
 * The [action] parameter is provided for context when a crash is associated with a
 * specific action dispatch, and null when the crash occurred in a coroutine launched
 * via `storeAccessor.launch` from a logic method.
 */
@ExperimentalReaktivApi
interface CrashListener {
    suspend fun onLogicCrash(exception: Throwable, action: ModuleAction?): CrashRecovery
}


/**
 * Marker interface for module state classes.
 *
 * States represent the data of your application at a given point in time.
 * They must be immutable and should be data classes marked with @Serializable.
 *
 * Example:
 * ```kotlin
 * @Serializable
 * data class CounterState(
 *     val count: Int = 0,
 *     val isLoading: Boolean = false,
 *     val error: String? = null
 * ) : ModuleState
 * ```
 */
interface ModuleState


/**
 * Marker interface for high-priority actions.
 *
 * Actions implementing this interface bypass the normal queue and are processed
 * immediately. Use this for time-sensitive operations like cancellations or
 * emergency stops.
 *
 * Example:
 * ```kotlin
 * sealed class UrgentAction : ModuleAction(UrgentModule::class), HighPriorityAction {
 *     data object CancelOperation : UrgentAction()
 *     data object EmergencyStop : UrgentAction()
 * }
 * ```
 */
interface HighPriorityAction


/**
 * Base class for module actions.
 *
 * Actions are events that describe changes in your application. They are dispatched
 * to the store to trigger state updates via reducers.
 *
 * Example:
 * ```kotlin
 * sealed class CounterAction : ModuleAction(CounterModule::class) {
 *     data object Increment : CounterAction()
 *     data object Decrement : CounterAction()
 *     data class SetCount(val value: Int) : CounterAction()
 * }
 * ```
 *
 * @param moduleTag The KClass of the module that handles this action
 */
@Serializable
abstract class ModuleAction(@Transient internal val moduleTag: KClass<*> = KClass::class)


typealias Dispatch = (ModuleAction) -> Unit
typealias DispatchSuspend = (suspend (ModuleAction) -> Unit)

/**
 * Result of a dispatch operation, indicating whether the action was processed.
 */
sealed class DispatchResult {
    /** Action was processed and applied to state */
    data object Processed : DispatchResult()

    /** Action was blocked by middleware (e.g., spam protection) */
    data object Blocked : DispatchResult()

    /** Action processing failed with an error */
    data class Error(val cause: Throwable) : DispatchResult()
}

/**
 * Internal envelope wrapping an action with an optional completion signal.
 * Used to track when async dispatch processing completes.
 */
internal data class DispatchEnvelope(
    val action: ModuleAction,
    val completion: CompletableDeferred<DispatchResult>?
)


interface Logic


/**
 * Base class for module logic implementations.
 *
 * Logic classes handle side effects, async operations, and complex business logic.
 * Define public suspend methods for operations that can be called from Composables
 * or other Logic classes.
 *
 * Example:
 * ```kotlin
 * class UserLogic(private val storeAccessor: StoreAccessor) : ModuleLogic() {
 *     private val api = UserApi()
 *
 *     suspend fun loadUser(userId: String) {
 *         storeAccessor.dispatch(UserAction.SetLoading(true))
 *         try {
 *             val user = api.fetchUser(userId)
 *             storeAccessor.dispatch(UserAction.SetUser(user))
 *         } catch (e: Exception) {
 *             storeAccessor.dispatch(UserAction.SetError(e.message))
 *         } finally {
 *             storeAccessor.dispatch(UserAction.SetLoading(false))
 *         }
 *     }
 *
 *     suspend fun logout() {
 *         api.logout()
 *         storeAccessor.dispatch(UserAction.ClearUser)
 *     }
 * }
 * ```
 *
 * @param A The action type this logic handles
 */
open class ModuleLogic : Logic {

    /**
     * Called on the **current** logic instance just before the store is reset.
     *
     * Override to clean up resources held by this logic instance — for example,
     * running lifecycle handlers, releasing observers, or clearing caches — before
     * the instance is discarded and a new one is created by [Module.createLogic].
     *
     * This is called after all operational coroutines have been cancelled and joined,
     * but before modules are reinitialized. Suspend calls are safe here.
     *
     * Example:
     * ```kotlin
     * class MyLogic(private val storeAccessor: StoreAccessor) : ModuleLogic() {
     *     private var observer: SomeObserver? = null
     *
     *     init {
     *         observer = SomeObserver()
     *     }
     *
     *     override suspend fun beforeReset() {
     *         observer?.release()
     *         observer = null
     *     }
     * }
     * ```
     */
    open suspend fun beforeReset() {}
}

/**
 * The recommended interface for defining modules with type-safe logic access.
 *
 * ModuleWithLogic extends Module with typed logic, allowing direct access to
 * logic methods without type casting. This is the preferred pattern for new modules.
 *
 * Example:
 * ```kotlin
 * @Serializable
 * data class CounterState(val count: Int = 0) : ModuleState
 *
 * sealed class CounterAction : ModuleAction(CounterModule::class) {
 *     data object Increment : CounterAction()
 *     data class SetCount(val value: Int) : CounterAction()
 * }
 *
 * class CounterLogic(private val storeAccessor: StoreAccessor) : ModuleLogic() {
 *     suspend fun incrementAsync() {
 *         delay(1000)
 *         storeAccessor.dispatch(CounterAction.Increment)
 *     }
 * }
 *
 * object CounterModule : ModuleWithLogic<CounterState, CounterAction, CounterLogic> {
 *     override val initialState = CounterState()
 *
 *     override val reducer: (CounterState, CounterAction) -> CounterState = { state, action ->
 *         when (action) {
 *             is CounterAction.Increment -> state.copy(count = state.count + 1)
 *             is CounterAction.SetCount -> state.copy(count = action.value)
 *         }
 *     }
 *
 *     override val createLogic: (StoreAccessor) -> CounterLogic = { CounterLogic(it) }
 * }
 * ```
 *
 * @param S The state type for this module (must implement ModuleState)
 * @param A The action type for this module (must extend ModuleAction)
 * @param L The logic type for this module (must extend ModuleLogic)
 */
interface ModuleWithLogic<S : ModuleState, A : ModuleAction, L : ModuleLogic> : Module<S, A> {

    override val createLogic: (StoreAccessor) -> L

    /**
     * Select the typed logic instance from the store.
     *
     * @param store The store to select logic from
     * @return The typed logic instance
     */
    suspend fun selectLogicTyped(store: StoreAccessor): L {
        @Suppress("UNCHECKED_CAST")
        return selectLogic(store) as L
    }
}

/**
 * Interface for defining modules in the MVLI architecture.
 *
 * A module owns a slice of application state, a reducer for transforming that state,
 * and a logic factory for creating the logic instance that handles side effects.
 *
 * For type-safe logic access without casting, prefer [ModuleWithLogic].
 */
interface Module<S : ModuleState, A : ModuleAction> {

    val initialState: S


    val reducer: (S, A) -> S


    val createLogic: (storeAccessor: StoreAccessor) -> ModuleLogic

    /**
     * Optional factory for creating a middleware provided by this module.
     *
     * When a module provides a middleware factory, the middleware will be created
     * and automatically registered with the Store during initialization. Module
     * middlewares are applied after explicitly registered middlewares (closer to
     * the reducer in the chain).
     *
     * The middleware can use `storeAccessor.selectLogic<T>()` to access Logic,
     * which will suspend until Logic is initialized.
     *
     * Example usage:
     * ```kotlin
     * class DevToolsModule(config: DevToolsConfig, scope: CoroutineScope)
     *     : ModuleWithLogic<DevToolsState, DevToolsAction, DevToolsLogic> {
     *
     *     override val createMiddleware: (() -> Middleware) = {
     *         DevToolsMiddleware(config, scope).middleware
     *     }
     * }
     * ```
     */
    val createMiddleware: (() -> Middleware)?
        get() = null

    fun selectStateFlowNonSuspend(store: StoreAccessor): StateFlow<S> {
        @Suppress("UNCHECKED_CAST")
        return store.getStateFlowForModule(this) as? StateFlow<S>
            ?: error("No state found for module $this")
    }

    suspend fun selectLogic(store: StoreAccessor): ModuleLogic {
        return store.getLogicForModule(this)
            ?: error("No logic found for module $this")
    }
}

internal data class ModuleInfo(
    val module: Module<*, *>,
    val state: MutableStateFlow<ModuleState>,
    var logic: ModuleLogic? = null
)


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
typealias Middleware = suspend (
    action: ModuleAction,
    getAllStates: suspend () -> Map<String, ModuleState>,
    storeAccessor: StoreAccessor,
    updatedState: suspend (ModuleAction) -> ModuleState,
) -> Unit


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
interface InternalStoreOperations {
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
    suspend fun applyExternalStates(states: Map<String, ModuleState>)
}


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
abstract class StoreAccessor(scope: CoroutineScope) : CoroutineScope {
    override val coroutineContext: CoroutineContext = scope.coroutineContext

    /**
     * Select a module's state flow by its class.
     *
     * @param stateClass The KClass of the state to select
     * @return StateFlow of the requested state type
     */
    abstract suspend fun <S : ModuleState> selectState(stateClass: KClass<S>): StateFlow<S>

    /**
     * Select a module's logic instance by its class.
     *
     * @param logicClass The KClass of the logic to select
     * @return The logic instance
     */
    abstract suspend fun <L : ModuleLogic> selectLogic(logicClass: KClass<L>): L

    /**
     * The dispatch function for sending actions to the store.
     * This is fire-and-forget - it returns immediately without waiting for processing.
     */
    abstract val dispatch: Dispatch

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
    abstract suspend fun dispatchAndAwait(action: ModuleAction): DispatchResult

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
    abstract suspend fun reset(): Boolean

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
    abstract fun resetAsync(): Job

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
    fun asInternalOperations(): InternalStoreOperations? = this as? InternalStoreOperations

    /**
     * Get a module instance by its class.
     *
     * Use this method from [StoreAccessor] references (e.g. inside Logic classes).
     *
     * Example usage:
     * ```kotlin
     * val navModule = storeAccessor.getModule(NavigationModule::class)
     *     ?: error("NavigationModule not registered")
     * ```
     *
     * @return The module instance if found, null otherwise
     */
    abstract fun <M : Any> getModule(moduleClass: KClass<M>): M?

    /**
     * Convenience reified overload for [getModule].
     *
     * Example usage:
     * ```kotlin
     * val navModule = storeAccessor.getModule<NavigationModule>()
     *     ?: error("NavigationModule not registered")
     * ```
     *
     * @return The module instance if found, null otherwise
     */
    inline fun <reified M : Any> getModule(): M? = getModule(M::class)

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
    abstract fun getStateFlowForModule(module: Module<*, *>): StateFlow<ModuleState>?

    /**
     * Returns the logic instance for the given module, suspending until the store
     * is fully initialized.
     *
     * @param module The module whose logic to retrieve
     * @return The logic instance, or null if the module is not registered
     */
    abstract suspend fun getLogicForModule(module: Module<*, *>): ModuleLogic?

    /**
     * Registers a [CrashListener] to be notified when logic invocation throws.
     */
    @ExperimentalReaktivApi
    abstract fun addCrashListener(listener: CrashListener)

    /**
     * Removes a previously registered [CrashListener].
     */
    @ExperimentalReaktivApi
    abstract fun removeCrashListener(listener: CrashListener)

}


@OptIn(ExperimentalReaktivApi::class)
class Store private constructor(
    private val coroutineScope: CoroutineScope,
    private val middlewares: List<Middleware>,
    @PublishedApi
    internal val modules: List<Module<ModuleState, ModuleAction>>,
    private val persistenceManager: PersistenceManager?,
    val serializersModule: SerializersModule,
) : StoreAccessor(coroutineScope), InternalStoreOperations {
    private val stateUpdateMutex = Mutex()
    private val resetMutex = Mutex()
    private val highPriorityChannel: Channel<DispatchEnvelope> = Channel(Channel.UNLIMITED)
    private val lowPriorityChannel: Channel<DispatchEnvelope> = Channel(Channel.UNLIMITED)
    private val moduleInfo: MutableMap<String, ModuleInfo> = mutableMapOf()

    private fun infoByModule(module: Module<*, *>): ModuleInfo? =
        moduleInfo[module::class.qualifiedName!!]

    private fun infoByModuleTag(moduleTag: KClass<*>): ModuleInfo? =
        moduleInfo[moduleTag.qualifiedName]

    private fun infoByStateClass(stateClass: KClass<*>): ModuleInfo? =
        moduleInfo[stateClass.qualifiedName]

    private fun infoByStateClassName(qualifiedName: String): ModuleInfo? =
        moduleInfo[qualifiedName]

    private fun infoByLogicClass(logicClass: KClass<*>): ModuleInfo? =
        moduleInfo[logicClass.qualifiedName]
    private val _initialized: MutableStateFlow<Boolean> = MutableStateFlow(false)
    val initialized: StateFlow<Boolean> = _initialized.asStateFlow()
    private val crashListeners = mutableListOf<CrashListener>()

    private val baseContext: CoroutineContext =
        coroutineScope.coroutineContext + CoroutineExceptionHandler { _, throwable ->
            if (crashListeners.isEmpty()) {
                throw throwable
            }
            CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
                val recovery = handleLogicException(throwable, null)
                if (recovery == CrashRecovery.RETHROW) {
                    throw throwable
                }
            }
        }

    private val storeJob: Job = SupervisorJob(coroutineScope.coroutineContext[Job])

    override val coroutineContext: CoroutineContext
        get() = baseContext + storeJob

    @ExperimentalReaktivApi
    override fun addCrashListener(listener: CrashListener) {
        crashListeners.add(listener)
    }

    @ExperimentalReaktivApi
    override fun removeCrashListener(listener: CrashListener) {
        crashListeners.remove(listener)
    }

    @OptIn(DelicateCoroutinesApi::class)
    override val dispatch: Dispatch = { action ->
        if (lowPriorityChannel.isClosedForSend || highPriorityChannel.isClosedForSend) {
            throw IllegalStateException("Store is closed")
        }

        val envelope = DispatchEnvelope(action, completion = null)
        launch {
            when (action) {
                is HighPriorityAction -> highPriorityChannel.send(envelope)
                else -> lowPriorityChannel.send(envelope)
            }
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    override suspend fun dispatchAndAwait(action: ModuleAction): DispatchResult {
        if (lowPriorityChannel.isClosedForSend || highPriorityChannel.isClosedForSend) {
            throw IllegalStateException("Store is closed")
        }

        val completion = CompletableDeferred<DispatchResult>()
        val envelope = DispatchEnvelope(action, completion)

        when (action) {
            is HighPriorityAction -> highPriorityChannel.send(envelope)
            else -> lowPriorityChannel.send(envelope)
        }

        return completion.await()
    }

    private suspend fun initializeModules() = stateUpdateMutex.withLock {
        modules.forEach { module ->
            val info = ModuleInfo(
                module = module,
                state = MutableStateFlow(module.initialState)
            )
            moduleInfo[module::class.qualifiedName!!] = info
            moduleInfo[module.initialState::class.qualifiedName!!] = info
        }

        modules.forEach { module ->
            val info = moduleInfo[module::class.qualifiedName!!]!!
            info.logic = module.createLogic(this)
            info.logic!!::class.qualifiedName?.let { moduleInfo[it] = info }
        }
        _initialized.update { true }
    }

    private suspend fun reinitializeModules() {
        _initialized.update { false }
        stateUpdateMutex.withLock {
            modules.forEach { module ->
                infoByModule(module)!!.state.update { module.initialState }
            }
            modules.forEach { module ->
                val info = infoByModule(module)!!
                info.logic = module.createLogic(this)
                info.logic!!::class.qualifiedName?.let { moduleInfo[it] = info }
            }
        }
        _initialized.update { true }
    }

    init {
        launch {
            initializeModules()
            processActionChannel()
        }
    }


    override suspend fun reset(): Boolean {
        if (!initialized.value) {
            throw IllegalArgumentException("Reset can not be called until the Store has been constructed!")
        }

        if (!resetMutex.tryLock()) {
            return false
        }

        return withContext(NonCancellable) {
            try {
                storeJob.cancelChildren(CancellationException("Store Reset"))
                yield()

                moduleInfo.values.distinctBy { it.module }.forEach { entry ->
                    entry.logic?.beforeReset()
                }

                crashListeners.clear()

                reinitializeModules()

                this@Store.launch { processActionChannel() }

                true
            } finally {
                resetMutex.unlock()
            }
        }
    }

    override fun resetAsync(): Job = launch {
        reset()
    }

    private suspend fun processActionChannel() = withContext(coroutineContext) {
        launch {
            for (envelope in highPriorityChannel) {
                processEnvelope(envelope)
            }
        }

        launch {
            for (envelope in lowPriorityChannel) {
                processEnvelope(envelope)
                yield()
            }
        }
    }

    private suspend fun processEnvelope(envelope: DispatchEnvelope) {
        try {
            val wasApplied = processAction(envelope.action)
            envelope.completion?.complete(
                if (wasApplied) DispatchResult.Processed else DispatchResult.Blocked
            )
        } catch (e: Throwable) {
            envelope.completion?.complete(DispatchResult.Error(e))
        }
    }

    /**
     * Process an action through the middleware chain.
     * @return true if the action was applied to state, false if blocked by middleware
     */
    private suspend fun processAction(action: ModuleAction): Boolean {
        var wasApplied = false
        val chain = createMiddlewareChain { wasApplied = true }
        chain(action)
        return wasApplied
    }

    private suspend fun createMiddlewareChain(
        onActionApplied: () -> Unit
    ): suspend (ModuleAction) -> Unit {
        val baseHandler: suspend (ModuleAction) -> Unit = { action ->
            val info = infoByModuleTag(action.moduleTag) ?: throw IllegalArgumentException(
                "No module found for action: ${action::class}"
            )

            val currentState = info.state.value
            val newState = (info.module.reducer as (ModuleState, ModuleAction) -> ModuleState)(currentState, action)
            updateState(newState::class.qualifiedName!!, newState)

            onActionApplied()
        }

        return middlewares.foldRight(baseHandler) { middleware, next ->
            { action: ModuleAction ->
                middleware(action, ::getAllStates, this) { innerAction ->
                    if (innerAction == action) {
                        next(innerAction)
                    } else {
                        dispatch(innerAction)
                    }
                    moduleInfo[action.moduleTag.qualifiedName]?.state?.value
                        ?: throw IllegalStateException("No state found for module: ${action.moduleTag}")
                }
            }
        }
    }

    private suspend fun handleLogicException(
        exception: Throwable,
        action: ModuleAction?
    ): CrashRecovery {
        var recovery = CrashRecovery.RETHROW
        for (listener in crashListeners) {
            try {
                val result = listener.onLogicCrash(exception, action)
                if (result == CrashRecovery.NAVIGATE_TO_CRASH_SCREEN) {
                    recovery = CrashRecovery.NAVIGATE_TO_CRASH_SCREEN
                }
            } catch (_: Exception) {
            }
        }
        return recovery
    }

    private suspend fun updateState(stateClass: String, newState: ModuleState) = stateUpdateMutex.withLock {
        infoByStateClassName(stateClass)?.state?.value = newState
    }

    private suspend fun getAllStates(): Map<String, ModuleState> = stateUpdateMutex.withLock {
        return@withLock moduleInfo.values.associate { it.module.initialState::class.qualifiedName!! to it.state.value }
    }

    @ExperimentalReaktivApi
    override suspend fun applyExternalStates(states: Map<String, ModuleState>) = stateUpdateMutex.withLock {
        states.forEach { (stateClassName, newState) ->
            val info = infoByStateClassName(stateClassName)

            when {
                info == null -> {
                    println("DevTools: Cannot apply state for unknown module: $stateClassName")
                }

                info.state.value::class != newState::class -> {
                    println("DevTools: State type mismatch for $stateClassName - expected ${info.state.value::class.simpleName}, got ${newState::class.simpleName}")
                }

                else -> {
                    info.state.value = newState
                }
            }
        }
    }


    @Suppress("UNCHECKED_CAST")
    override suspend fun <S : ModuleState> selectState(stateClass: KClass<S>): StateFlow<S> {
        initialized.first { it }
        stateUpdateMutex.lock()
        try {
            return selectStateNonSuspend(stateClass)
        } finally {
            stateUpdateMutex.unlock()
        }
    }

    fun <S : ModuleState> selectStateNonSuspend(stateClass: KClass<S>): StateFlow<S> {
        val info = infoByStateClass(stateClass)
        @Suppress("UNCHECKED_CAST")
        return info?.state?.asStateFlow() as? StateFlow<S>
            ?: throw IllegalStateException(
                "No state found for state class: ${stateClass.qualifiedName}, stateExists: ${info != null}"
            )
    }


    suspend inline fun <reified S : ModuleState> selectState(): StateFlow<S> = selectState(S::class)

    inline fun <reified S : ModuleState> selectStateNonSuspend(): StateFlow<S> = selectStateNonSuspend(S::class)


    @Suppress("UNCHECKED_CAST")
    override suspend fun <L : ModuleLogic> selectLogic(logicClass: KClass<L>): L {
        initialized.first { it }
        stateUpdateMutex.lock()
        try {
            return infoByLogicClass(logicClass)?.logic as? L
                ?: throw IllegalStateException("No logic found for logic class: $logicClass")
        } finally {
            stateUpdateMutex.unlock()
        }
    }

    override fun <M : Any> getModule(moduleClass: KClass<M>): M? {
        @Suppress("UNCHECKED_CAST")
        return modules.firstOrNull { moduleClass.isInstance(it) } as M?
    }

    override fun getStateFlowForModule(module: Module<*, *>): StateFlow<ModuleState>? =
        infoByModule(module)?.state?.asStateFlow()

    override suspend fun getLogicForModule(module: Module<*, *>): ModuleLogic? {
        initialized.first { it }
        return infoByModule(module)?.logic
    }

    suspend inline fun <reified L : ModuleLogic> selectLogic(): L = selectLogic(L::class)

    fun cleanup() {
        coroutineScope.cancel()
        highPriorityChannel.close()
        lowPriorityChannel.close()
    }


    suspend fun saveState(state: Map<String, ModuleState>) {
        persistenceManager?.persistState(state) ?: throw IllegalStateException("No persistence strategy set")
    }


    suspend fun loadState() {
        val restoredState = persistenceManager?.restoreState()
        if (restoredState == null) {
            println("Warning, no persistence strategy set when using loadState")
        }
        restoredState?.forEach { (key, state) ->
            updateState(key, state)
        }
    }


    suspend fun hasPersistedState(): Boolean = persistenceManager?.hasPersistedState() ?: false

    companion object {
        internal fun create(
            coroutineScope: CoroutineScope,
            middlewares: List<Middleware>,
            modules: List<Module<ModuleState, ModuleAction>>,
            persistenceManager: PersistenceManager?,
            serializersModule: SerializersModule,
        ): Store {
            return Store(
                coroutineScope = coroutineScope,
                middlewares = middlewares,
                modules = modules.toList(),
                persistenceManager = persistenceManager,
                serializersModule = serializersModule,
            )
        }
    }
}


class StoreDSL {
    private var coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val middlewares = mutableListOf<Middleware>()
    private val modules: MutableList<Module<ModuleState, ModuleAction>> = mutableListOf()
    private var persistenceStrategy: PersistenceStrategy? = null
    private val moduleStateRegistrations = mutableMapOf<String, (PolymorphicModuleBuilder<ModuleState>) -> Unit>()
    private val customTypeRegistrars = mutableListOf<CustomTypeRegistrar>()

    @OptIn(InternalSerializationApi::class)

    fun <S : ModuleState, A : ModuleAction> module(
        stateClass: KClass<S>,
        module: Module<S, A>
    ) {
        val stateClassName = module.initialState::class.qualifiedName
            ?: throw IllegalArgumentException("Module state class must have a qualified name")

        if (moduleStateRegistrations.containsKey(stateClassName)) {
            throw IllegalArgumentException(
                "Duplicate module state registration detected: $stateClassName. " +
                        "Each state class can only be registered once. " +
                        "Check that you're not adding the same module multiple times or using the same state class in different modules."
            )
        }

        modules.add(module as Module<ModuleState, ModuleAction>)
        moduleStateRegistrations[stateClassName] = { builder ->
            @Suppress("UNCHECKED_CAST")
            val actualStateClass = module.initialState::class as KClass<S>
            builder.subclass(actualStateClass, actualStateClass.serializer() as KSerializer<S>)
        }

        if (module is CustomTypeRegistrar) {
            customTypeRegistrars.add(module)
        }
    }


    inline fun <reified S : ModuleState, A : ModuleAction> module(module: Module<S, A>) {
        module(S::class, module)
    }


    fun middlewares(vararg newMiddlewares: Middleware) {
        middlewares.addAll(newMiddlewares)
    }


    fun coroutineContext(context: CoroutineContext) {
        coroutineScope = CoroutineScope(SupervisorJob() + context.minusKey(Job))
    }


    fun persistenceManager(persistenceStrategy: PersistenceStrategy) {
        this.persistenceStrategy = persistenceStrategy
    }

    internal fun build(): Store {
        val serializersModule = SerializersModule {
            polymorphic(ModuleState::class) {
                moduleStateRegistrations.values.forEach { it(this) }
            }
            customTypeRegistrars.forEach { registrar ->
                registrar.registerAdditionalSerializers(this)
            }
        }

        val persistenceManager = persistenceStrategy?.let {
            PersistenceManager(
                json = Json {
                    ignoreUnknownKeys = true
                    this.serializersModule = serializersModule
                },
                persistenceStrategy = it
            )
        }

        // Combine explicit middlewares with module-provided middlewares
        // Explicit middlewares run first (outer), module middlewares run after (inner/closer to reducer)
        val moduleMiddlewares = modules.mapNotNull { it.createMiddleware?.invoke() }
        val allMiddlewares = middlewares + moduleMiddlewares

        return Store.create(coroutineScope, allMiddlewares, modules, persistenceManager, serializersModule)
    }
}


/**
 * Creates a new Store instance using the DSL builder.
 *
 * The store is the central piece of the Reaktiv architecture. It manages state,
 * handles actions, and coordinates between different modules.
 *
 * Example:
 * ```kotlin
 * val store = createStore {
 *     module(CounterModule)
 *     module(UserModule)
 *     module(navigationModule)
 *
 *     middlewares(loggingMiddleware, analyticsMiddleware)
 *     coroutineContext(Dispatchers.Default)
 *     persistenceManager(PlatformPersistenceStrategy())
 * }
 * ```
 *
 * @param block DSL block for configuring the store
 * @return The configured Store instance
 */
fun createStore(block: StoreDSL.() -> Unit): Store {
    val dsl = StoreDSL().apply(block)
    return dsl.build()
}