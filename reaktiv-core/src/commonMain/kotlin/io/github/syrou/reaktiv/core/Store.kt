package io.github.syrou.reaktiv.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import kotlin.reflect.KClass

interface ModuleState

abstract class ModuleAction(val moduleTag: KClass<*>)
typealias Dispatch = (ModuleAction) -> Unit

abstract class ModuleLogic<A : ModuleAction> {
    lateinit var dispatch: Dispatch
    abstract suspend operator fun invoke(action: ModuleAction, dispatch: Dispatch)

    companion object {
        operator fun <A : ModuleAction> invoke(logic: suspend (ModuleAction, Dispatch) -> Unit): ModuleLogic<A> {
            return object : ModuleLogic<A>() {
                override suspend fun invoke(action: ModuleAction, dispatch: Dispatch) {
                    logic(action, dispatch)
                }
            }
        }
    }
}

interface Module<S : ModuleState, A : ModuleAction> {
    val initialState: S
    val reducer: (S, A) -> S
    val logic: ModuleLogic<A>
}

data class ModuleInfo(
    val module: Module<*, *>,
    val state: MutableStateFlow<ModuleState>,
    val logic: ModuleLogic<out ModuleAction>
)

typealias Middleware = suspend (
    action: ModuleAction,
    getState: Map<KClass<out ModuleState>, ModuleState>,
    next: suspend (ModuleAction) -> ModuleState
) -> ModuleState

/**
 * The central component of the Kotlin Store Library that manages the application state and handles state updates.
 *
 * The Store coordinates the interaction between [Module]s, [Middleware], and the application logic.
 * It provides methods for dispatching actions, selecting state, and accessing module logic.
 *
 * @property coroutineScope The [CoroutineScope] used for launching coroutines within the Store.
 * @property middlewares A list of [Middleware] functions that can intercept and process actions.
 * @property moduleInfo A map containing information about all registered modules.
 * @property dispatcher A function for dispatching actions within the Store.
 * @property actionChannel A channel for handling actions asynchronously.
 *
 * @see Module
 * @see Middleware
 */
class Store private constructor(
    private val coroutineScope: CoroutineScope,
    private val middlewares: List<Middleware>,
    private val moduleInfo: Map<KClass<*>, ModuleInfo>,
    private val actionChannel: Channel<ModuleAction>,
) {
    private val stateUpdateMutex = Mutex()
    val dispatcher: Dispatch = { action ->
        coroutineScope.launch {
            actionChannel.send(action)
        }
    }

    init {
        moduleInfo.values.forEach { info ->
            info.logic.dispatch = dispatcher
        }
        coroutineScope.launch {
            processActionChannel()
        }
    }

    private suspend fun processActionChannel() = withContext(coroutineScope.coroutineContext) {
        for (action in actionChannel) {
            val newState = applyMiddlewares(action)
            updateState(newState::class, newState)
        }
    }

    private suspend fun applyMiddlewares(action: ModuleAction): ModuleState {
        val chain = createMiddlewareChain()
        return chain(action)
    }

    private suspend fun createMiddlewareChain(): suspend (ModuleAction) -> ModuleState {
        return middlewares.foldRight(
            { a: ModuleAction -> processAction(a) }
        ) { middleware, next ->
            { action: ModuleAction ->
                middleware(action, getAllStates()) { innerAction ->
                    if (innerAction == action) {
                        next(innerAction)
                    } else {
                        dispatcher.invoke(innerAction)
                        //dispatchSuspend(innerAction)
                        moduleInfo[innerAction::class]?.state?.value
                            ?: throw IllegalStateException("No state found for module: ${action.moduleTag}")
                    }
                }
            }
        }
    }

    private suspend fun processAction(action: ModuleAction): ModuleState {
        val info = moduleInfo[action.moduleTag]
            ?: throw IllegalArgumentException("No module found for action: ${action::class}")

        val currentState = info.state.value

        @Suppress("UNCHECKED_CAST")
        val newState = (info.module.reducer as (ModuleState, ModuleAction) -> ModuleState)(currentState, action)

        coroutineScope.launch(coroutineScope.coroutineContext) {
            info.logic.invoke(action, dispatcher)
        }

        return newState
    }

    private suspend fun updateState(stateClass: KClass<out ModuleState>, newState: ModuleState) {
        stateUpdateMutex.withLock {
            moduleInfo[stateClass]?.state?.value = newState
        }
    }

    private fun getAllStates(): Map<KClass<out ModuleState>, ModuleState> {
        return moduleInfo.values.associate { it.module.initialState::class to it.state.value }
    }

    /**
     * Selects the state of a specific module as a [StateFlow].
     *
     * @param S The type of the [ModuleState] to select.
     * @return A [StateFlow] of the requested [ModuleState].
     * @throws IllegalStateException if no state is found for the given state class.
     *
     * Example usage:
     * ```kotlin
     * val store = createStore { /* ... */ }
     * val counterState: StateFlow<CounterState> = store.selectState(CounterState::class)
     *
     * // In a coroutine
     * counterState.collect { state ->
     *     println("Current count: ${state.count}")
     * }
     * ```
     */
    @Suppress("UNCHECKED_CAST")
    fun <S : ModuleState> selectState(stateClass: KClass<S>): StateFlow<S> {
        return moduleInfo[stateClass]?.state as? StateFlow<S>
            ?: throw IllegalStateException("No state found for state class: $stateClass")
    }

    /**
     * Selects the state of a specific module as a [StateFlow] using reified type parameters.
     *
     * This is a convenience method that allows you to select state without explicitly
     * passing the state class.
     *
     * @param S The type of the [ModuleState] to select.
     * @return A [StateFlow] of the requested [ModuleState].
     * @throws IllegalStateException if no state is found for the given state class.
     *
     * Example usage:
     * ```kotlin
     * val store = createStore { /* ... */ }
     * val counterState: StateFlow<CounterState> = store.selectState<CounterState>()
     *
     * // In a coroutine
     * counterState.collect { state ->
     *     println("Current count: ${state.count}")
     * }
     * ```
     */
    inline fun <reified S : ModuleState> selectState(): StateFlow<S> = selectState(S::class)

    /**
     * Selects the logic of a specific module.
     *
     * @param L The type of the [ModuleLogic] to select.
     * @return The requested [ModuleLogic] instance.
     * @throws IllegalStateException if no logic is found for the given logic class.
     *
     * Example usage:
     * ```kotlin
     * val store = createStore { /* ... */ }
     * val counterLogic: CounterLogic = store.selectLogic(CounterLogic::class)
     *
     * // Use the logic
     * counterLogic.someCustomMethod()
     * ```
     */
    @Suppress("UNCHECKED_CAST")
    fun <L : ModuleLogic<out ModuleAction>> selectLogic(logicClass: KClass<L>): L {
        return moduleInfo[logicClass]?.logic as? L
            ?: throw IllegalStateException("No logic found for logic class: $logicClass")
    }

    /**
     * Selects the logic of a specific module using reified type parameters.
     *
     * This is a convenience method that allows you to select logic without explicitly
     * passing the logic class.
     *
     * @param L The type of the [ModuleLogic] to select.
     * @return The requested [ModuleLogic] instance.
     * @throws IllegalStateException if no logic is found for the given logic class.
     *
     * Example usage:
     * ```kotlin
     * val store = createStore { /* ... */ }
     * val counterLogic: CounterLogic = store.selectLogic<CounterLogic>()
     *
     * // Use the logic
     * counterLogic.someCustomMethod()
     * ```
     */
    inline fun <reified L : ModuleLogic<out ModuleAction>> selectLogic(): L = selectLogic(L::class)

    /**
     * Cleans up resources used by the Store.
     *
     * This method should be called when the Store is no longer needed to prevent memory leaks
     * and ensure proper resource management.
     *
     * Example usage:
     * ```kotlin
     * val store = createStore { /* ... */ }
     * // Use the store...
     * store.cleanup()
     * ```
     */
    fun cleanup() {
        coroutineScope.cancel()
        actionChannel.close()
    }

    companion object {
        /**
         * Creates a new instance of the Store.
         *
         * This method is internal and should not be called directly. Use the [createStore] function instead.
         */
        internal fun create(
            coroutineScope: CoroutineScope,
            middlewares: List<Middleware>,
            moduleInfo: Map<KClass<*>, ModuleInfo>,
            actionChannel: Channel<ModuleAction>,
        ): Store {
            return Store(
                coroutineScope = coroutineScope,
                middlewares = middlewares,
                moduleInfo = moduleInfo,
                actionChannel = actionChannel
            )
        }
    }
}

/**
 * A Domain Specific Language (DSL) for creating and configuring a [Store] instance.
 *
 * The StoreDSL provides a convenient and readable way to set up a Store with its modules,
 * middlewares, and coroutine context. It is used in conjunction with the [createStore] function.
 *
 * @property middlewares A mutable list of [Middleware] functions to be applied to the Store.
 * @property coroutineContext The [CoroutineContext] to be used by the Store for launching coroutines.
 * @property moduleInfo A mutable map containing information about all registered modules.
 * @property actionChannel A channel for handling actions asynchronously.
 *
 * @see Store
 * @see Middleware
 * @see Module
 */
class StoreDSL {
    private lateinit var coroutineScope: CoroutineScope
    private val middlewares = mutableListOf<Middleware>()
    private val moduleInfo = mutableMapOf<KClass<*>, ModuleInfo>()
    private val actionChannel = Channel<ModuleAction>(Channel.UNLIMITED)

    /**
     * Registers one or more modules with the Store.
     *
     * This method allows you to add multiple modules to the Store at once. Each module
     * represents a distinct piece of state and its associated logic.
     *
     * @param newModules Vararg parameter of [Module] instances to be added to the Store.
     *
     * Example usage:
     * ```kotlin
     * createStore {
     *     modules(
     *         CounterModule,
     *         UserModule,
     *         SettingsModule
     *     )
     * }
     * ```
     */
    fun modules(vararg newModules: Module<*, *>) {
        newModules.forEach { module ->
            val info = ModuleInfo(
                module = module,
                state = MutableStateFlow(module.initialState),
                logic = module.logic
            )

            moduleInfo[module::class] = info
            moduleInfo[module.initialState::class] = info
            moduleInfo[module.logic::class] = info
        }
    }

    /**
     * Adds one or more middleware functions to the Store.
     *
     * Middleware functions allow you to intercept and process actions before they reach
     * the reducers, enabling side effects, logging, or other cross-cutting concerns.
     *
     * @param newMiddlewares Vararg parameter of [Middleware] functions to be added to the Store.
     *
     * Example usage:
     * ```kotlin
     * createStore {
     *     middlewares(
     *         loggingMiddleware,
     *         analyticsMiddleware,
     *         thunkMiddleware
     *     )
     * }
     * ```
     */
    fun middlewares(vararg newMiddlewares: Middleware) {
        middlewares.addAll(newMiddlewares)
    }

    /**
     * Sets the coroutine context for the Store.
     *
     * This context will be used by the Store when launching coroutines for processing
     * actions and managing state updates.
     *
     * @param context The [CoroutineContext] to be used by the Store.
     *
     * Example usage:
     * ```kotlin
     * createStore {
     *     coroutineContext(Dispatchers.Default + SupervisorJob())
     * }
     * ```
     */
    fun coroutineContext(context: CoroutineContext) {
        coroutineScope = CoroutineScope(context)
    }

    /**
     * Builds and returns a [Store] instance based on the configured modules, middlewares,
     * and coroutine context.
     *
     * This method is internal and is called by the [createStore] function after the DSL
     * block has been executed.
     *
     * If the user did not provide a context, create one for them
     *
     * @return A fully configured [Store] instance.
     */
    internal fun build(): Store {
        if (!::coroutineScope.isInitialized) {
            coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        }
        return Store.create(coroutineScope, middlewares, moduleInfo, actionChannel)
    }
}

/**
 * Creates and configures a [Store] instance using the StoreDSL.
 *
 * This function provides a convenient way to set up a Store with its modules,
 * middlewares, and coroutine context using a DSL-style configuration.
 *
 * @param block A lambda with receiver of type [StoreDSL] where you can configure the Store.
 * @return A fully configured [Store] instance.
 *
 * Example usage:
 * ```kotlin
 * val store = createStore {
 *     modules(CounterModule, UserModule)
 *     middlewares(loggingMiddleware, thunkMiddleware)
 *     coroutineContext(Dispatchers.Default + SupervisorJob())
 * }
 * ```
 */
fun createStore(block: StoreDSL.() -> Unit): Store {
    val dsl = StoreDSL().apply(block)
    return dsl.build()
}