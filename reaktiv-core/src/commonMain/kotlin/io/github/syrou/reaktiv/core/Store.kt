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


interface Module<S : ModuleState, A : ModuleAction> {

    val initialState: S


    val reducer: (S, A) -> S


    val createLogic: (storeAccessor: StoreAccessor) -> ModuleLogic<A>

    val selectStateFlow: suspend (Store) -> StateFlow<S>
        get() = { store ->
            @Suppress("UNCHECKED_CAST")
            store.selectState(initialState::class as KClass<S>)
        }
}

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


abstract class StoreAccessor(scope: CoroutineScope) : CoroutineScope {
    override val coroutineContext: CoroutineContext = scope.coroutineContext


    abstract suspend fun <S : ModuleState> selectState(stateClass: KClass<S>): StateFlow<S>


    abstract suspend fun <L : ModuleLogic<out ModuleAction>> selectLogic(logicClass: KClass<L>): L


    abstract val dispatch: Dispatch
}


class Store private constructor(
    private val coroutineScope: CoroutineScope,
    private val middlewares: List<Middleware>,
    private val modules: List<Module<ModuleState, ModuleAction>>,
    private val persistenceManager: PersistenceManager?,
) : StoreAccessor(coroutineScope) {
    private val stateUpdateMutex = Mutex()
    private val highPriorityChannel: Channel<ModuleAction> = Channel(Channel.UNLIMITED)
    private val lowPriorityChannel: Channel<ModuleAction> = Channel<ModuleAction>(Channel.UNLIMITED)
    private val moduleInfo: MutableMap<String, ModuleInfo> = mutableMapOf()
    private val _initialized: MutableStateFlow<Boolean> = MutableStateFlow(false)
    val initialized: StateFlow<Boolean> = _initialized.asStateFlow()


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


class StoreDSL {
    private var coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val middlewares = mutableListOf<Middleware>()
    private val modules: MutableList<Module<ModuleState, ModuleAction>> = mutableListOf()
    private var persistenceStrategy: PersistenceStrategy? = null
    private val moduleStateRegistrations = mutableListOf<(PolymorphicModuleBuilder<ModuleState>) -> Unit>()
    private val customTypeRegistrars = mutableListOf<CustomTypeRegistrar>()

    @OptIn(InternalSerializationApi::class)

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


fun createStore(block: StoreDSL.() -> Unit): Store {
    val dsl = StoreDSL().apply(block)
    return dsl.build()
}