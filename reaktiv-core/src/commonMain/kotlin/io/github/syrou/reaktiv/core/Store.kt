package io.github.syrou.reaktiv.core

import io.github.syrou.reaktiv.core.persistance.PersistenceManager
import io.github.syrou.reaktiv.core.util.CustomTypeRegistrar
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
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.PolymorphicModuleBuilder
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.serializer
import kotlin.coroutines.CoroutineContext
import kotlin.reflect.KClass

/**
 * Represents the state of a module in the Reaktiv architecture.
 *
 * Example:
 * ```
 * data class CounterState(val count: Int) : ModuleState
 * ```
 */
interface ModuleState

/**
 * Represents an action that can be dispatched to modify the state of a module.
 *
 * @property moduleTag The [KClass] of the module that this action is associated with.
 *
 * Example:
 * ```
 * sealed class CounterAction : ModuleAction(CounterModule::class) {
 *     object Increment : CounterAction()
 *     object Decrement : CounterAction()
 * }
 * ```
 */
abstract class ModuleAction(val moduleTag: KClass<*>)

typealias Dispatch = (ModuleAction) -> Unit

/**
 * Defines the logic for handling actions in a module.
 *
 * @param A The type of [ModuleAction] that this logic handles.
 *
 * Example:
 * ```
 * class CounterLogic : ModuleLogic<CounterAction>() {
 *     override suspend fun invoke(action: ModuleAction, dispatch: Dispatch) {
 *         when (action) {
 *             is CounterAction.Increment -> println("Incrementing")
 *             is CounterAction.Decrement -> println("Decrementing")
 *         }
 *     }
 * }
 * ```
 */

interface Logic {
    var dispatch: Dispatch
    suspend operator fun invoke(action: ModuleAction, dispatch: Dispatch)
}

open class ModuleLogic<A : ModuleAction> : Logic {
    override lateinit var dispatch: Dispatch
    override suspend fun invoke(action: ModuleAction, dispatch: Dispatch) {
        //dispatch.invoke(action)
    }

    /**
     * Creates a [ModuleLogic] instance from a suspending function.
     *
     * @param logic The function that defines the logic behavior.
     * @return A new [ModuleLogic] instance.
     *
     * Example:
     * ```
     * val counterLogic = ModuleLogic<CounterAction> { action, dispatch ->
     *     when (action) {
     *         is CounterAction.Increment -> println("Incrementing")
     *         is CounterAction.Decrement -> println("Decrementing")
     *     }
     * }
     * ```
     */
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

/**
 * Defines the structure of a module in the Reaktiv architecture.
 *
 * @param S The type of [ModuleState] for this module.
 * @param A The type of [ModuleAction] that this module handles.
 *
 * Example:
 * ```
 * object CounterModule : Module<CounterState, CounterAction> {
 *     override val initialState = CounterState(0)
 *     override val reducer: (CounterState, CounterAction) -> CounterState = { state, action ->
 *         when (action) {
 *             is CounterAction.Increment -> state.copy(count = state.count + 1)
 *             is CounterAction.Decrement -> state.copy(count = state.count - 1)
 *         }
 *     }
 *     override val logic = ModuleLogic<CounterAction> { _, _ -> /* No side effects */ }
 * }
 * ```
 */
interface Module<S : ModuleState, A : ModuleAction> {
    /** The initial state of the module. */
    val initialState: S

    /** The reducer function that computes a new state based on the current state and an action. */
    val reducer: (S, A) -> S

    /** The logic that handles side effects for this module. */
    val logic: ModuleLogic<A>
}

internal data class ModuleInfo(
    val module: Module<*, *>,
    val state: MutableStateFlow<ModuleState>,
    val logic: ModuleLogic<out ModuleAction>
)

/**
 * Represents a middleware function that can intercept and process actions before they reach the reducer.
 *
 * Example:
 * ```
 * val loggingMiddleware: Middleware = { action, getState, next ->
 *     println("Action: $action")
 *     println("State before: ${getState()}")
 *     val result = next(action)
 *     println("State after: ${getState()}")
 *     result
 * }
 * ```
 */
typealias Middleware = suspend (
    action: ModuleAction,
    getState: Map<String, ModuleState>,
    next: suspend (ModuleAction) -> ModuleState
) -> ModuleState

/**
 * The central class of the Reaktiv architecture, managing the state and logic of all modules.
 *
 * This class is not meant to be instantiated directly. Use [createStore] to create a Store instance.
 *
 * Example usage:
 * ```
 * val store = createStore {
 *     module(CounterModule)
 *     middlewares(loggingMiddleware)
 *     coroutineContext(Dispatchers.Default)
 * }
 *
 * // Dispatch an action
 * store.dispatcher(CounterAction.Increment)
 *
 * // Select state
 * val counterState = store.selectState<CounterState>()
 * println("Current count: ${counterState.value.count}")
 * ```
 */
class Store private constructor(
    private val coroutineScope: CoroutineScope,
    private val middlewares: List<Middleware>,
    private val moduleInfo: Map<String, ModuleInfo>,
    private val actionChannel: Channel<ModuleAction>,
    private val persistenceManager: PersistenceManager?,
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
            updateState(newState::class.qualifiedName!!, newState)
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
                        moduleInfo[innerAction::class.qualifiedName]?.state?.value
                            ?: throw IllegalStateException("No state found for module: ${action.moduleTag}")
                    }
                }
            }
        }
    }

    private suspend fun processAction(action: ModuleAction): ModuleState {
        val info = moduleInfo[action.moduleTag.qualifiedName]
            ?: throw IllegalArgumentException("No module found for action: ${action::class}")

        val currentState = info.state.value

        @Suppress("UNCHECKED_CAST")
        val newState = (info.module.reducer as (ModuleState, ModuleAction) -> ModuleState)(currentState, action)


        coroutineScope.launch(coroutineScope.coroutineContext) {
            info.logic.invoke(action, dispatcher)
        }

        return newState
    }

    private suspend fun updateState(stateClass: String, newState: ModuleState) {
        stateUpdateMutex.withLock {
            moduleInfo[stateClass]?.state?.value = newState
        }
    }

    private fun getAllStates(): Map<String, ModuleState> {
        return moduleInfo.values.associate { it.module.initialState::class.qualifiedName!! to it.state.value }
    }

    @Suppress("UNCHECKED_CAST")
    fun <S : ModuleState> selectState(stateClass: KClass<S>): StateFlow<S> {
        return moduleInfo[stateClass.qualifiedName]?.state as? StateFlow<S>
            ?: throw IllegalStateException("No state found for state class: $stateClass")
    }

    inline fun <reified S : ModuleState> selectState(): StateFlow<S> = selectState(S::class)

    @Suppress("UNCHECKED_CAST")
    fun <L : ModuleLogic<out ModuleAction>> selectLogic(logicClass: KClass<L>): L {
        return moduleInfo[logicClass.qualifiedName]?.logic as? L
            ?: throw IllegalStateException("No logic found for logic class: $logicClass")
    }

    inline fun <reified L : ModuleLogic<out ModuleAction>> selectLogic(): L = selectLogic(L::class)

    fun cleanup() {
        coroutineScope.cancel()
        actionChannel.close()
    }

    suspend fun persistState() {
        persistenceManager?.persistState(getAllStates())
    }

    suspend fun restoreState() {
        val restoredState = persistenceManager?.restoreState()
        println("TESTOR - RESTORED STATE: $restoredState")
        restoredState?.forEach { (key, state) ->
            updateState(key, state)
        }
    }

    companion object {
        internal fun create(
            coroutineScope: CoroutineScope,
            middlewares: List<Middleware>,
            moduleInfo: Map<String, ModuleInfo>,
            actionChannel: Channel<ModuleAction>,
            persistenceManager: PersistenceManager?,
        ): Store {
            return Store(
                coroutineScope = coroutineScope,
                middlewares = middlewares,
                moduleInfo = moduleInfo,
                actionChannel = actionChannel,
                persistenceManager = persistenceManager
            )
        }
    }
}

/**
 * A DSL for configuring and creating a [Store] instance.
 * This class is not meant to be used directly. Use [createStore] to create a Store instance.
 */
class StoreDSL {
    private lateinit var coroutineScope: CoroutineScope
    private val middlewares = mutableListOf<Middleware>()
    private val moduleInfo = mutableMapOf<String, ModuleInfo>()
    private val actionChannel = Channel<ModuleAction>(Channel.UNLIMITED)
    private var persistenceManager: PersistenceManager? = null
    private val moduleStateRegistrations = mutableListOf<(PolymorphicModuleBuilder<ModuleState>) -> Unit>()
    private val customTypeRegistrars = mutableListOf<CustomTypeRegistrar>()

    @OptIn(InternalSerializationApi::class)
    fun <S : ModuleState, A : ModuleAction> module(
        stateClass: KClass<S>,
        module: Module<S, A>
    ) {
        val info = ModuleInfo(
            module = module,
            state = MutableStateFlow(module.initialState),
            logic = module.logic
        )

        moduleInfo[module::class.qualifiedName!!] = info
        moduleInfo[module.initialState::class.qualifiedName!!] = info
        module.logic::class.qualifiedName?.let { moduleInfo[it] = info }

        moduleStateRegistrations.add { builder ->
            @Suppress("UNCHECKED_CAST")
            builder.subclass(stateClass, module.initialState::class.serializer() as KSerializer<S>)
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
        coroutineScope = CoroutineScope(context)
    }


    fun persistenceManager(manager: PersistenceManager) {
        persistenceManager = manager.copy(
            json = Json {
                ignoreUnknownKeys = true
                serializersModule = SerializersModule {
                    polymorphic(ModuleState::class) {
                        moduleStateRegistrations.forEach { it(this) }
                    }
                    customTypeRegistrars.forEach { registrar ->
                        registrar.registerAdditionalSerializers(this)
                    }
                }
            }
        )
    }

    internal fun build(): Store {
        if (!::coroutineScope.isInitialized) {
            coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        }
        return Store.create(coroutineScope, middlewares, moduleInfo, actionChannel, persistenceManager)
    }
}

/**
 * Creates a new [Store] instance using the provided configuration block.
 *
 * @param block The configuration block for setting up the store.
 * @return A new [Store] instance.
 *
 * Example:
 * ```
 * val store = createStore {
 *     module(CounterModule)
 *     middlewares(loggingMiddleware)
 *     coroutineContext(Dispatchers.Default)
 *     persistenceManager(MyPersistenceManager())
 * }
 *
 * // Use the store
 * store.dispatcher(CounterAction.Increment)
 * val counterState = store.selectState<CounterState>()
 * println("Counter value: ${counterState.value.count}")
 * ```
 */
fun createStore(block: StoreDSL.() -> Unit): Store {
    val dsl = StoreDSL().apply(block)
    return dsl.build()
}