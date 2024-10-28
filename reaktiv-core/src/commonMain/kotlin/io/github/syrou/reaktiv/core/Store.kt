package io.github.syrou.reaktiv.core

import io.github.syrou.reaktiv.core.persistance.PersistenceManager
import io.github.syrou.reaktiv.core.persistance.PersistenceStrategy
import io.github.syrou.reaktiv.core.util.CustomTypeRegistrar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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
 * Represents the state of a module in the Reaktiv framework.
 */
interface ModuleState

/**
 * Represents an action that can be dispatched to a module in the Reaktiv framework.
 *
 * @property moduleTag The KClass of the module this action is associated with.
 */
abstract class ModuleAction(internal val moduleTag: KClass<*>)

/**
 * A function type alias for dispatching actions in the Reaktiv framework.
 */
typealias Dispatch = (ModuleAction) -> Unit


/**
 * Defines the logic interface for handling actions in the Reaktiv framework.
 */
interface Logic {
    /**
     * Invokes the logic with the given action.
     *
     * @param action The action to be processed.
     */
    suspend operator fun invoke(action: ModuleAction)
}


/**
 * Represents the logic for a specific module in the Reaktiv framework.
 *
 * @param A The type of action this logic handles.
 */
open class ModuleLogic<A : ModuleAction> : Logic {
    override suspend fun invoke(action: ModuleAction) {
        //Leave this empty
    }

    companion object {
        /**
         * Creates a new ModuleLogic instance with the given logic function.
         *
         * @param logic The function to be executed when the logic is invoked.
         * @return A new ModuleLogic instance.
         *
         * Example:
         * ```
         * val myLogic = ModuleLogic<MyAction> { action ->
         *     when (action) {
         *         is MyAction.Increment -> // Handle increment
         *         is MyAction.Decrement -> // Handle decrement
         *     }
         * }
         * ```
         */
        operator fun <A : ModuleAction> invoke(logic: suspend (ModuleAction) -> Unit): ModuleLogic<A> {
            return object : ModuleLogic<A>() {
                override suspend fun invoke(action: ModuleAction) {
                    logic(action)
                }
            }
        }
    }
}

/**
 * Defines the structure of a module in the Reaktiv framework.
 *
 * @param S The type of state managed by this module.
 * @param A The type of action this module can handle.
 */
interface Module<S : ModuleState, A : ModuleAction> {
    /**
     * The initial state of the module.
     */
    val initialState: S

    /**
     * The reducer function that defines how the state changes in response to actions.
     */
    val reducer: (S, A) -> S

    /**
     * Creates the logic for this module.
     *
     * @param dispatch The dispatch function to be used by the logic.
     * @return The created ModuleLogic instance.
     */
    val createLogic: (storeAccessor: StoreAccessor) -> ModuleLogic<A>
}

internal data class ModuleInfo(
    val module: Module<*, *>,
    val state: MutableStateFlow<ModuleState>,
    var logic: ModuleLogic<out ModuleAction>? = null
)

/**
 * Represents a middleware in the Reaktiv framework.
 *
 * @param action The action being processed.
 * @param getAllStates A function to retrieve all current states.
 * @param updatedState A function to get the updated state after processing an action.
 */
typealias Middleware = suspend (
    action: ModuleAction,
    getAllStates: suspend () -> Map<String, ModuleState>,
    dispatch: Dispatch,
    updatedState: suspend (ModuleAction) -> ModuleState,
) -> Unit

/**
 * Interface providing access to core Store functionality.
 * This interface allows modules and their logic classes to interact with the Store
 * without exposing the entire Store implementation.
 */
interface StoreAccessor {
    /**
     * Selects the state of a specific module.
     *
     * @param S The type of state to select.
     * @param stateClass The KClass of the state to select.
     * @return A StateFlow of the selected state.
     * @throws IllegalStateException if no state is found for the given class.
     */
    fun <S : ModuleState> selectState(stateClass: KClass<S>): StateFlow<S>

    /**
     * Selects the logic of a specific module.
     *
     * @param L The type of logic to select.
     * @param logicClass The KClass of the logic to select.
     * @return The selected logic instance.
     * @throws IllegalStateException if no logic is found for the given class.
     */
    fun <L : ModuleLogic<out ModuleAction>> selectLogic(logicClass: KClass<L>): L

    /**
     * Dispatches an action to be processed by the Store.
     *
     * @param action The action to be dispatched.
     */
    val dispatch: Dispatch
}

/**
 * The main store class for managing state and actions in the Reaktiv framework.
 */
class Store private constructor(
    private val coroutineScope: CoroutineScope,
    private val middlewares: List<Middleware>,
    private val modules: List<Module<ModuleState, ModuleAction>>,
    private val persistenceManager: PersistenceManager?,
) : StoreAccessor {
    private val stateUpdateMutex = Mutex()
    private val actionChannel: Channel<ModuleAction> = Channel<ModuleAction>(Channel.UNLIMITED)
    private val moduleInfo: MutableMap<String, ModuleInfo> = mutableMapOf()

    /**
     * A function type alias for dispatching actions in the Reaktiv framework.
     * This lambda is used for easy integration with Jetpack Compose.
     */
    @OptIn(DelicateCoroutinesApi::class)
    override val dispatch: Dispatch = { action ->
        if (actionChannel.isClosedForSend) {
            throw IllegalStateException("Store is closed")
        }

        coroutineScope.launch(coroutineScope.coroutineContext) {
            actionChannel.send(action)
        }
    }

    private fun initializeModules() {
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
    }

    init {
        initializeModules()
        coroutineScope.launch {
            processActionChannel()
        }
    }

    private suspend fun processActionChannel() = withContext(coroutineScope.coroutineContext) {
        for (action in actionChannel) {
            processAction(action)
        }
    }

    private suspend fun processAction(action: ModuleAction) {
        val chain = createMiddlewareChain()
        chain(action)
    }

    private suspend fun createMiddlewareChain(): suspend (ModuleAction) -> Unit {
        val baseHandler: suspend (ModuleAction) -> Unit = { action ->
            val info = moduleInfo[action.moduleTag.qualifiedName]
                ?: throw IllegalArgumentException("No module found for action: ${action::class.qualifiedName}")

            val currentState = info.state.value

            val newState = (info.module.reducer as (ModuleState, ModuleAction) -> ModuleState)(currentState, action)
            updateState(newState::class.qualifiedName!!, newState)
            coroutineScope.launch(coroutineScope.coroutineContext) {
                info.logic?.invoke(action)
            }
        }

        return middlewares.foldRight(baseHandler) { middleware, next ->
            { action: ModuleAction ->
                middleware(action, ::getAllStates, dispatch) { innerAction ->
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

    private suspend fun updateState(stateClass: String, newState: ModuleState) {
        stateUpdateMutex.withLock {
            moduleInfo[stateClass]?.state?.update { newState }
        }
    }

    private suspend fun getAllStates(): Map<String, ModuleState> {
        stateUpdateMutex.withLock {
            return moduleInfo.values.associate { it.module.initialState::class.qualifiedName!! to it.state.value }
        }
    }

    /**
     * Selects the state of a specific module.
     *
     * @param S The type of state to select.
     * @param stateClass The KClass of the state to select.
     * @return A StateFlow of the selected state.
     *
     * Example:
     * ```
     * val counterState: StateFlow<CounterState> = store.selectState(CounterState::class)
     * ```
     */
    @Suppress("UNCHECKED_CAST")
    override fun <S : ModuleState> selectState(stateClass: KClass<S>): StateFlow<S> {
        val retrievedState = moduleInfo[stateClass.qualifiedName]?.state
        val stateExists = retrievedState != null
        val mapped = moduleInfo.map { it.key }
        return retrievedState?.asStateFlow() as? StateFlow<S> ?: run {
            throw IllegalStateException(
                """
                    No state found for state class: ${stateClass.qualifiedName},
                    retrievedState: $retrievedState,
                    stateExists: $stateExists,
                    available states: $mapped   
                """.trimIndent()
            )
        }
    }

    /**
     * Selects the state of a specific module using reified type parameter.
     *
     * @param S The type of state to select.
     * @return A StateFlow of the selected state.
     *
     * Example:
     * ```
     * val counterState: StateFlow<CounterState> = store.selectState()
     * ```
     */
    inline fun <reified S : ModuleState> selectState(): StateFlow<S> = selectState(S::class)

    /**
     * Selects the logic of a specific module.
     *
     * @param L The type of logic to select.
     * @param logicClass The KClass of the logic to select.
     * @return The selected logic instance.
     *
     * Example:
     * ```
     * val counterLogic: CounterLogic = store.selectLogic(CounterLogic::class)
     * ```
     */
    @Suppress("UNCHECKED_CAST")
    override fun <L : ModuleLogic<out ModuleAction>> selectLogic(logicClass: KClass<L>): L {
        return moduleInfo[logicClass.qualifiedName]?.logic as? L
            ?: throw IllegalStateException("No logic found for logic class: $logicClass")
    }

    /**
     * Selects the logic of a specific module using reified type parameter.
     *
     * @param L The type of logic to select.
     * @return The selected logic instance.
     *
     * Example:
     * ```
     * val counterLogic: CounterLogic = store.selectLogic()
     * ```
     */
    inline fun <reified L : ModuleLogic<out ModuleAction>> selectLogic(): L = selectLogic(L::class)

    /**
     * Cleans up resources used by the store.
     */
    fun cleanup() {
        coroutineScope.cancel()
        actionChannel.close()
    }

    /**
     * Saves the current state of all modules.
     *
     * @param state The state to be saved.
     *
     * Example:
     * ```
     * store.saveState(mapOf("CounterState" to CounterState(count = 5)))
     * ```
     */
    suspend fun saveState(state: Map<String, ModuleState>) {
        persistenceManager?.persistState(state) ?: throw IllegalStateException("No persistence strategy set")
    }

    /**
     * Loads the previously saved state for all modules.
     *
     * Example:
     * ```
     * store.loadState()
     * ```
     */
    suspend fun loadState() {
        val restoredState = persistenceManager?.restoreState()
        if (restoredState == null) {
            println("Warning, no persistence strategy set when using loadState")
        }
        restoredState?.forEach { (key, state) ->
            updateState(key, state)
        }
    }

    /**
     * Checks if there is a previously loaded state.
     *
     * Example:
     * ```
     * store.hasPersistedState()
     * ```
     */
    suspend fun hasPersistedState(): Boolean = persistenceManager?.hasPersistedState() ?: false

    companion object {
        internal fun create(
            coroutineScope: CoroutineScope,
            middlewares: List<Middleware>,
            modules: List<Module<ModuleState, ModuleAction>>,
            persistenceManager: PersistenceManager?,
        ): Store {
            return Store(
                coroutineScope = coroutineScope,
                middlewares = middlewares,
                modules = modules.toList(),
                persistenceManager = persistenceManager,
            )
        }
    }
}

/**
 * A DSL for configuring and building a Store instance.
 */
class StoreDSL {
    private var coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val middlewares = mutableListOf<Middleware>()
    private val modules: MutableList<Module<ModuleState, ModuleAction>> = mutableListOf()
    private var persistenceStrategy: PersistenceStrategy? = null
    private val moduleStateRegistrations = mutableListOf<(PolymorphicModuleBuilder<ModuleState>) -> Unit>()
    private val customTypeRegistrars = mutableListOf<CustomTypeRegistrar>()

    @OptIn(InternalSerializationApi::class)
            /**
             * Adds a module to the store configuration.
             *
             * @param S The type of state managed by the module.
             * @param A The type of action handled by the module.
             * @param stateClass The KClass of the module's state.
             * @param module The module instance to be added.
             *
             */
    fun <S : ModuleState, A : ModuleAction> module(
        stateClass: KClass<S>,
        module: Module<S, A>
    ) {
        modules.add(module as Module<ModuleState, ModuleAction>)
        moduleStateRegistrations.add { builder ->
            @Suppress("UNCHECKED_CAST")
            builder.subclass(stateClass, module.initialState::class.serializer() as KSerializer<S>)
        }

        if (module is CustomTypeRegistrar) {
            customTypeRegistrars.add(module)
        }
    }


    /**
     * Adds a module to the store configuration using reified type parameters.
     *
     * @param S The type of state managed by the module.
     * @param A The type of action handled by the module.
     * @param module The module instance to be added.
     *
     */
    inline fun <reified S : ModuleState, A : ModuleAction> module(module: Module<S, A>) {
        module(S::class, module)
    }

    /**
     * Adds middlewares to the store configuration.
     *
     * @param newMiddlewares The middlewares to be added.
     *
     */
    fun middlewares(vararg newMiddlewares: Middleware) {
        middlewares.addAll(newMiddlewares)
    }

    /**
     * Sets the coroutine context for the store.
     *
     * @param context The CoroutineContext to be used.
     *
     */
    fun coroutineContext(context: CoroutineContext) {
        coroutineScope = CoroutineScope(context)
    }

    /**
     * Sets the persistence strategy for the store.
     *
     * @param persistenceStrategy The PersistenceStrategy to be used.
     *
     */
    fun persistenceManager(persistenceStrategy: PersistenceStrategy) {
        this.persistenceStrategy = persistenceStrategy
    }

    internal fun build(): Store {
        val persistenceManager = persistenceStrategy?.let {
            PersistenceManager(
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
                },
                persistenceStrategy = it
            )
        }
        return Store.create(coroutineScope, middlewares, modules, persistenceManager)
    }
}

/**
 * Creates a new Store instance using the provided configuration.
 *
 * @param block The configuration block for setting up the store.
 * @return A new Store instance.
 *
 * Example:
 * ```
 * val store = createStore {
 *     module<CounterState, CounterAction>(CounterModule)
 *     middlewares(loggingMiddleware)
 *     coroutineContext(Dispatchers.Default)
 *     persistenceManager(FilePersistenceStrategy("app_state.json"))
 * }
 * ```
 */
fun createStore(block: StoreDSL.() -> Unit): Store {
    val dsl = StoreDSL().apply(block)
    return dsl.build()
}