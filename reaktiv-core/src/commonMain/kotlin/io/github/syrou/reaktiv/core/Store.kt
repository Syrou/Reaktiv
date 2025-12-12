@file:OptIn(ExperimentalCoroutinesApi::class)

package io.github.syrou.reaktiv.core

import io.github.syrou.reaktiv.core.persistance.PersistenceManager
import io.github.syrou.reaktiv.core.persistance.PersistenceStrategy
import io.github.syrou.reaktiv.core.util.CustomTypeRegistrar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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


interface ModuleState


interface HighPriorityAction


@Serializable
abstract class ModuleAction(@Transient internal val moduleTag: KClass<*> = KClass::class)


typealias Dispatch = (ModuleAction) -> Unit
typealias DispatchSuspend = (suspend (ModuleAction) -> Unit)


interface Logic {

    suspend operator fun invoke(action: ModuleAction)
}


open class ModuleLogic<A : ModuleAction> : Logic {
    override suspend fun invoke(action: ModuleAction) {
    }

    companion object {

        operator fun <A : ModuleAction> invoke(logic: suspend (ModuleAction) -> Unit): ModuleLogic<A> {
            return object : ModuleLogic<A>() {
                override suspend fun invoke(action: ModuleAction) {
                    logic(action)
                }
            }
        }
    }
}

interface ModuleWithLogic<S : ModuleState, A : ModuleAction, L : ModuleLogic<A>> : Module<S, A> {

    override val createLogic: (StoreAccessor) -> L

    suspend fun selectLogicTyped(store: Store): L {
        @Suppress("UNCHECKED_CAST")
        return super.selectLogic(store) as L
    }
}

interface Module<S : ModuleState, A : ModuleAction> {

    val initialState: S


    val reducer: (S, A) -> S


    val createLogic: (storeAccessor: StoreAccessor) -> ModuleLogic<A>

    suspend fun selectStateFlow(store: Store): StateFlow<S> {
        @Suppress("UNCHECKED_CAST")
        return store.selectState(initialState::class as KClass<S>)
    }

    fun selectStateFlowNonSuspend(store: Store): StateFlow<S> {
        @Suppress("UNCHECKED_CAST")
        return store.selectStateNonSuspend(initialState::class as KClass<S>)
    }

    suspend fun selectLogic(store: Store): ModuleLogic<A> {
        @Suppress("UNCHECKED_CAST")
        return store.selectLogicThroughState(initialState::class as KClass<ModuleState>) as ModuleLogic<A>
    }
}

/**
 * Extension of Module that allows custom merging of externally synced state.
 *
 * Used when modules need to preserve local state properties that shouldn't be
 * replaced by synced state (e.g., DevTools state sync where graph definitions
 * with Composable layouts should be preserved locally).
 *
 * Usage:
 * ```kotlin
 * object NavigationModule : StatefulModule<NavigationState, NavigationAction> {
 *     override fun mergeExternalState(local: NavigationState, synced: NavigationState): NavigationState {
 *         return synced.copy(
 *             graphDefinitions = local.graphDefinitions
 *         )
 *     }
 * }
 * ```
 */
interface StatefulModule<S : ModuleState, A : ModuleAction> : Module<S, A> {
    /**
     * Merges externally synced state with local state.
     *
     * @param local The current local state with properties that should be preserved
     * @param synced The incoming synced state from external source
     * @return Merged state combining synced updates with preserved local properties
     */
    fun mergeExternalState(local: S, synced: S): S
}

/**
 * Combination of StatefulModule and ModuleWithLogic.
 * Use this when you need both typed logic access and custom state merging.
 *
 * Usage:
 * ```kotlin
 * object NavigationModule : StatefulModuleWithLogic<NavigationState, NavigationAction, NavigationLogic> {
 *     override fun mergeExternalState(local: NavigationState, synced: NavigationState): NavigationState {
 *         return synced.copy(graphDefinitions = local.graphDefinitions)
 *     }
 * }
 * ```
 */
interface StatefulModuleWithLogic<S : ModuleState, A : ModuleAction, L : ModuleLogic<A>> :
    StatefulModule<S, A>, ModuleWithLogic<S, A, L>

internal data class ModuleInfo(
    val module: Module<*, *>,
    val state: MutableStateFlow<ModuleState>,
    var logic: ModuleLogic<out ModuleAction>? = null
)


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


abstract class StoreAccessor(scope: CoroutineScope) : CoroutineScope {
    override val coroutineContext: CoroutineContext = scope.coroutineContext


    abstract suspend fun <S : ModuleState> selectState(stateClass: KClass<S>): StateFlow<S>


    abstract suspend fun <L : ModuleLogic<out ModuleAction>> selectLogic(logicClass: KClass<L>): L


    abstract val dispatch: Dispatch

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
}


@OptIn(ExperimentalReaktivApi::class)
class Store private constructor(
    private val coroutineScope: CoroutineScope,
    private val middlewares: List<Middleware>,
    private val modules: List<Module<ModuleState, ModuleAction>>,
    private val persistenceManager: PersistenceManager?,
    val serializersModule: SerializersModule,
) : StoreAccessor(coroutineScope), InternalStoreOperations {
    private val stateUpdateMutex = Mutex()
    private val highPriorityChannel: Channel<ModuleAction> = Channel(Channel.UNLIMITED)
    private val lowPriorityChannel: Channel<ModuleAction> = Channel<ModuleAction>(Channel.UNLIMITED)
    private val moduleInfo: MutableMap<String, ModuleInfo> = mutableMapOf()
    private val _initialized: MutableStateFlow<Boolean> = MutableStateFlow(false)
    val initialized: StateFlow<Boolean> = _initialized.asStateFlow()

    fun debug(){
        modules.forEach { module ->
            println("module state: ${module.initialState}")
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    override val dispatch: Dispatch = { action ->
        if (lowPriorityChannel.isClosedForSend || highPriorityChannel.isClosedForSend) {
            throw IllegalStateException("Store is closed")
        }

        launch {
            when (action) {
                is HighPriorityAction -> highPriorityChannel.send(action)
                else -> lowPriorityChannel.send(action)
            }
        }
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
        moduleInfo.forEach { entry ->
            println("moduleInfo: ${entry.key} - ${entry.value.state.value}")
        }
        _initialized.update { true }
    }

    init {
        launch {
            initializeModules()
            processActionChannel()
        }
    }


    fun reset() {
        if (!initialized.value) {
            throw IllegalArgumentException("Reset can not be called until the Store has been constructed!")
        }
        coroutineContext.cancelChildren(CancellationException("Store Reset"))
        launch {
            processActionChannel()
        }
    }

    private suspend fun processActionChannel() = withContext(coroutineContext) {
        launch {
            for (action in highPriorityChannel) {
                processAction(action)
            }
        }

        launch {
            for (action in lowPriorityChannel) {
                processAction(action)

                yield()
            }
        }
    }

    private suspend fun processAction(action: ModuleAction) {
        val chain = createMiddlewareChain()
        chain(action)
    }

    private suspend fun createMiddlewareChain(): suspend (ModuleAction) -> Unit {
        val baseHandler: suspend (ModuleAction) -> Unit = { action ->
            val info = moduleInfo[action.moduleTag.qualifiedName] ?: throw IllegalArgumentException(
                "No module found for action: ${action::class}"
            )

            val currentState = info.state.value
            val newState = (info.module.reducer as (ModuleState, ModuleAction) -> ModuleState)(currentState, action)
            updateState(newState::class.qualifiedName!!, newState)

            launch {
                info.logic?.invoke(action)
            }
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

    private suspend fun updateState(stateClass: String, newState: ModuleState) = stateUpdateMutex.withLock {
        moduleInfo[stateClass]?.state?.value = newState
    }

    private suspend fun getAllStates(): Map<String, ModuleState> = stateUpdateMutex.withLock {
        return@withLock moduleInfo.values.associate { it.module.initialState::class.qualifiedName!! to it.state.value }
    }

    @ExperimentalReaktivApi
    override suspend fun applyExternalStates(states: Map<String, ModuleState>) = stateUpdateMutex.withLock {
        states.forEach { (stateClassName, newState) ->
            val info = moduleInfo[stateClassName]

            when {
                info == null -> {
                    println("DevTools: Cannot apply state for unknown module: $stateClassName")
                }
                info.state.value::class != newState::class -> {
                    println("DevTools: State type mismatch for $stateClassName - expected ${info.state.value::class.simpleName}, got ${newState::class.simpleName}")
                }
                else -> {
                    val finalState = if (info.module is StatefulModule<*, *>) {
                        @Suppress("UNCHECKED_CAST")
                        (info.module as StatefulModule<ModuleState, *>).mergeExternalState(
                            local = info.state.value,
                            synced = newState
                        )
                    } else {
                        newState
                    }
                    info.state.value = finalState
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
        val retrievedState = moduleInfo[stateClass.qualifiedName]?.state
        val stateExists = retrievedState != null
        return moduleInfo[stateClass.qualifiedName]?.state?.asStateFlow() as? StateFlow<S>
            ?: throw IllegalStateException(
                """
                    No state found for state class: ${stateClass.qualifiedName},
                    retrievedState: $retrievedState,
                    stateExists: $stateExists 
                """.trimIndent()
            )
    }


    suspend inline fun <reified S : ModuleState> selectState(): StateFlow<S> = selectState(S::class)

    inline fun <reified S : ModuleState> selectStateNonSuspend(): StateFlow<S> = selectStateNonSuspend(S::class)


    @Suppress("UNCHECKED_CAST")
    override suspend fun <L : ModuleLogic<out ModuleAction>> selectLogic(logicClass: KClass<L>): L {
        initialized.first { it }
        stateUpdateMutex.lock()
        try {
            return moduleInfo[logicClass.qualifiedName]?.logic as? L
                ?: throw IllegalStateException("No logic found for logic class: $logicClass")
        } finally {
            stateUpdateMutex.unlock()
        }
    }

    @Suppress("UNCHECKED_CAST")
    suspend fun <S : ModuleState> selectLogicThroughState(stateClass: KClass<S>): ModuleLogic<out ModuleAction> {
        initialized.first { it }
        stateUpdateMutex.lock()
        try {
            return moduleInfo[stateClass.qualifiedName]?.logic
                ?: throw IllegalStateException("No logic found for state class: $stateClass")
        } finally {
            stateUpdateMutex.unlock()
        }
    }


    suspend inline fun <reified L : ModuleLogic<out ModuleAction>> selectLogic(): L = selectLogic(L::class)


    fun cleanup() {
        coroutineScope.cancel()
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
        return Store.create(coroutineScope, middlewares, modules, persistenceManager, serializersModule)
    }
}


fun createStore(block: StoreDSL.() -> Unit): Store {
    val dsl = StoreDSL().apply(block)
    return dsl.build()
}