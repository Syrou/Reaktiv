@file:OptIn(ExperimentalCoroutinesApi::class, ExperimentalAtomicApi::class)

package io.github.syrou.reaktiv.core

import io.github.syrou.reaktiv.core.persistance.PersistenceManager
import io.github.syrou.reaktiv.core.util.CopyOnWriteRegistry
import io.github.syrou.reaktiv.core.util.ReaktivDebug
import io.github.syrou.reaktiv.core.util.currentTimeMillis
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
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
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlinx.serialization.modules.SerializersModule
import kotlin.coroutines.CoroutineContext
import kotlin.reflect.KClass

@OptIn(ExperimentalReaktivApi::class)
public class Store private constructor(
    private val coroutineScope: CoroutineScope,
    private val middlewares: List<Middleware>,
    @PublishedApi
    internal val modules: List<Module<ModuleState, ModuleAction>>,
    private val persistenceManager: PersistenceManager?,
    public val serializersModule: SerializersModule,
) : StoreAccessor(coroutineScope), InternalStoreOperations {
    private val resetMutex = Mutex()
    private val highPriorityChannel: Channel<DispatchEnvelope> = Channel(Channel.UNLIMITED)
    private val lowPriorityChannel: Channel<DispatchEnvelope> = Channel(Channel.UNLIMITED)

    private val moduleInfo: Map<String, ModuleInfo> = buildMap {
        modules.forEach { module ->
            val info = ModuleInfo(module, MutableStateFlow(module.initialState))
            put(module::class.qualifiedName!!, info)
            put(module.initialState::class.qualifiedName!!, info)
        }
    }

    private val logicIndex = AtomicReference<Map<String, ModuleInfo>>(emptyMap())

    private val moduleInfos: List<ModuleInfo> = moduleInfo.values.distinct()

    private fun info(key: KClass<*>): ModuleInfo? = key.qualifiedName?.let(::info)

    private fun info(qualifiedName: String): ModuleInfo? =
        moduleInfo[qualifiedName] ?: logicIndex.load()[qualifiedName]

    private val _initialized: MutableStateFlow<Boolean> = MutableStateFlow(false)
    public val initialized: StateFlow<Boolean> = _initialized.asStateFlow()

    private val constructed = AtomicBoolean(false)
    private val crashListeners = CopyOnWriteRegistry<CrashListener>()

    private val crashScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val baseContext: CoroutineContext =
        coroutineScope.coroutineContext + CoroutineExceptionHandler { _, throwable ->
            if (crashListeners.isEmpty) {
                throw throwable
            }
            crashScope.launch {
                val recovery = handleLogicException(throwable, null)
                if (recovery == CrashRecovery.RETHROW) {
                    throw throwable
                }
            }
        }

    private val storeJob: Job = SupervisorJob(coroutineScope.coroutineContext[Job])

    private val pipelineJob: Job = SupervisorJob(coroutineScope.coroutineContext[Job])

    private val dispatchEnqueuedCount = AtomicLong(0L)
    private val dispatchProcessedCount = AtomicLong(0L)

    private val externalControlMutex = Mutex()
    private val externallyDriven = AtomicBoolean(false)
    private val dispatchInstrumentation = AtomicReference<DispatchInstrumentation?>(null)

    /**
     * `true` while a remote publisher authors this store's state.
     *
     * @see InternalStoreOperations.beginExternalControl
     */
    public val isExternallyDriven: Boolean
        get() = externallyDriven.load()

    @ExperimentalReaktivApi
    override fun markExternallyDriven() {
        externallyDriven.store(true)
    }

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

    private fun enqueue(action: ModuleAction, completion: CompletableDeferred<DispatchResult>?) {
        val target = if (action is HighPriorityAction) highPriorityChannel else lowPriorityChannel
        val envelope = DispatchEnvelope(
            action,
            completion,
            enqueuedAtMs = if (instrumentationActive()) currentTimeMillis() else 0L
        )
        if (target.trySend(envelope).isFailure) {
            throw IllegalStateException("Store is closed")
        }
        dispatchEnqueuedCount.addAndFetch(1L)
    }

    override val dispatch: Dispatch = { action -> enqueue(action, completion = null) }

    override suspend fun dispatchAndAwait(action: ModuleAction): DispatchResult {
        val completion = CompletableDeferred<DispatchResult>()
        enqueue(action, completion)
        return completion.await()
    }

    private fun initializeModules(resetState: Boolean) {
        if (resetState) {
            moduleInfos.forEach { info -> info.state.update { info.module.initialState } }
        }
        val logicKeys = mutableMapOf<String, ModuleInfo>()
        moduleInfos.forEach { info ->
            val logic = info.module.createLogic(this)
            info.logic.store(logic)
            logic::class.qualifiedName?.let { logicKeys[it] = info }
        }
        logicIndex.store(logicKeys)
        constructed.store(true)
        _initialized.update { true }
    }

    init {
        launch { initializeModules(resetState = false) }
        CoroutineScope(baseContext + pipelineJob).launch { processActionChannel() }
    }

    override suspend fun reset(): Boolean {
        if (!constructed.load()) {
            throw IllegalArgumentException("Reset can not be called until the Store has been constructed!")
        }

        if (!resetMutex.tryLock()) {
            return false
        }

        return withContext(NonCancellable) {
            try {
                _initialized.update { false }
                externallyDriven.store(false)
                cancelLogic()

                moduleInfos.forEach { it.logic.load()?.beforeReset() }

                initializeModules(resetState = true)

                true
            } finally {
                resetMutex.unlock()
            }
        }
    }

    private suspend fun cancelLogic() {
        storeJob.cancelChildren(CancellationException("Store Reset"))
        yield()
    }

    override fun resetAsync(): Job = launch {
        reset()
    }

    private suspend fun processActionChannel() {
        while (true) {
            val envelope = highPriorityChannel.tryReceive().getOrNull()
                ?: select {
                    highPriorityChannel.onReceiveCatching { it.getOrNull() }
                    lowPriorityChannel.onReceiveCatching { it.getOrNull() }
                }
                ?: return
            if (!_initialized.value) {
                initialized.first { it }
            }
            processEnvelope(envelope)
            yield()
        }
    }

    /**
     * The installed [DispatchInstrumentation], or `null` when none is installed or the installed one
     * reports [DispatchInstrumentation.isActive] as `false`.
     *
     * Modules that emit spans for work outside the dispatch pipeline read this before doing any
     * work, so nothing is built when nothing is listening.
     *
     * Usage:
     * ```kotlin
     * val instrumentation = (storeAccessor as? Store)?.activeDispatchInstrumentation
     *     ?: return evaluate()
     * val token = instrumentation.onEvaluationStarted("MyScope", "evaluate", emptyMap())
     * ```
     *
     * @see setDispatchInstrumentation to install one
     */
    public val activeDispatchInstrumentation: DispatchInstrumentation?
        get() = dispatchInstrumentation.load()?.takeIf { it.isActive }

    private fun instrumentationActive(): Boolean = activeDispatchInstrumentation != null

    /**
     * Installs the instrumentation that observes this store, or `null` to remove it.
     *
     * @param instrumentation The implementation to install, replacing any previous one
     * @see activeDispatchInstrumentation
     */
    public fun setDispatchInstrumentation(instrumentation: DispatchInstrumentation?) {
        dispatchInstrumentation.store(instrumentation)
    }

    private suspend fun processEnvelope(envelope: DispatchEnvelope) {
        val instrumentation = activeDispatchInstrumentation
        if (externallyDriven.load() && envelope.action !is ExternalControlExempt) {
            instrumentation?.onDispatchDropped(envelope.action)
            envelope.completion?.complete(DispatchResult.Blocked)
            dispatchProcessedCount.addAndFetch(1L)
            return
        }
        var token = ""
        var processStartMs = 0L
        if (instrumentation != null) {
            processStartMs = currentTimeMillis()
            val queueWaitMs = if (envelope.enqueuedAtMs > 0L) {
                (processStartMs - envelope.enqueuedAtMs).coerceAtLeast(0L)
            } else 0L
            val queueDepth = (dispatchEnqueuedCount.load() - dispatchProcessedCount.load())
                .coerceAtLeast(1L)
            token = instrumentation.onDispatchStarted(envelope.action, queueWaitMs, queueDepth)
        }
        try {
            val wasApplied = processAction(envelope.action, instrumentation)
            if (token.isNotEmpty()) {
                instrumentation?.onDispatchCompleted(token, wasApplied, currentTimeMillis() - processStartMs)
            }
            envelope.completion?.complete(
                if (wasApplied) DispatchResult.Processed else DispatchResult.Blocked
            )
        } catch (e: Throwable) {
            if (token.isNotEmpty()) {
                instrumentation?.onDispatchFailed(token, e, currentTimeMillis() - processStartMs)
            }
            envelope.completion?.complete(DispatchResult.Error(e))
        } finally {
            dispatchProcessedCount.addAndFetch(1L)
        }
    }

    /**
     * Process an action through the middleware chain.
     * @return true if the action was applied to state, false if blocked by middleware
     */
    private suspend fun processAction(
        action: ModuleAction,
        instrumentation: DispatchInstrumentation?
    ): Boolean {
        var wasApplied = false
        val chain = createMiddlewareChain(instrumentation?.newDispatchDecorator()) { wasApplied = true }
        chain(action)
        return wasApplied
    }

    private fun createMiddlewareChain(
        decorator: DispatchStepDecorator?,
        onActionApplied: () -> Unit
    ): suspend (ModuleAction) -> Unit {
        val baseHandler: suspend (ModuleAction) -> Unit = { action ->
            val info = info(action.moduleTag) ?: throw IllegalArgumentException(
                "No module found for action: ${action::class}"
            )

            @Suppress("UNCHECKED_CAST")
            val reducer = info.module.reducer as (ModuleState, ModuleAction) -> ModuleState
            info.state.update { current -> reducer(current, action) }

            onActionApplied()
        }

        val innermost = decorator?.decorate("reducer", baseHandler) ?: baseHandler
        return middlewares.foldRightIndexed(innermost) { index, middleware, next ->
            val step: suspend (ModuleAction) -> Unit = { action ->
                middleware(action, { getAllStates() }, this) { innerAction ->
                    if (innerAction == action) {
                        next(innerAction)
                    } else {
                        dispatch(innerAction)
                    }
                    info(action.moduleTag)?.state?.value
                        ?: throw IllegalStateException("No state found for module: ${action.moduleTag}")
                }
            }
            if (decorator == null) {
                step
            } else {
                val simpleName = middleware::class.simpleName?.takeIf { it.isNotBlank() } ?: "middleware"
                decorator.decorate("$simpleName[$index]", step)
            }
        }
    }

    private suspend fun handleLogicException(
        exception: Throwable,
        action: ModuleAction?
    ): CrashRecovery {
        var recovery = CrashRecovery.RETHROW
        for (listener in crashListeners.snapshot()) {
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

    private fun applyState(stateClassName: String, newState: ModuleState, source: String) {
        val info = info(stateClassName)
        when {
            info == null ->
                ReaktivDebug.warn("$source: Cannot apply state for unknown module: $stateClassName")

            info.state.value::class != newState::class ->
                ReaktivDebug.warn(
                    "$source: State type mismatch for $stateClassName - " +
                        "expected ${info.state.value::class.simpleName}, got ${newState::class.simpleName}"
                )

            else -> info.state.value = newState
        }
    }

    private fun getAllStates(): Map<String, ModuleState> =
        moduleInfos.associate { it.module.initialState::class.qualifiedName!! to it.state.value }

    @ExperimentalReaktivApi
    override suspend fun applyExternalStates(states: Map<String, ModuleState>) {
        states.forEach { (stateClassName, newState) -> applyState(stateClassName, newState, "DevTools") }
    }

    @ExperimentalReaktivApi
    override suspend fun beginExternalControl(): Unit = externalControlMutex.withLock {
        if (externallyDriven.load()) return@withLock
        notifyExternalControl(true)
        externallyDriven.store(true)
        traceExternalControl(true)
    }

    @ExperimentalReaktivApi
    override suspend fun endExternalControl(): Unit = externalControlMutex.withLock {
        if (!externallyDriven.load()) return@withLock
        externallyDriven.store(false)
        traceExternalControl(false)
        notifyExternalControl(false)
    }

    private suspend fun notifyExternalControl(enabled: Boolean) {
        moduleInfos.forEach { entry ->
            try {
                entry.logic.load()?.onExternalControlChanged(enabled)
            } catch (e: Exception) {
                ReaktivDebug.warn("Store: onExternalControlChanged failed - ${e.message}")
            }
        }
    }

    private suspend fun traceExternalControl(enabled: Boolean) {
        dispatchInstrumentation.load()?.takeIf { it.isActive }?.onExternalControlChanged(enabled)
    }

    override suspend fun <S : ModuleState> selectState(stateClass: KClass<S>): StateFlow<S> {
        initialized.first { it }
        return selectStateNonSuspend(stateClass)
    }

    public fun <S : ModuleState> selectStateNonSuspend(stateClass: KClass<S>): StateFlow<S> {
        @Suppress("UNCHECKED_CAST")
        return info(stateClass)?.state?.asStateFlow() as StateFlow<S>?
            ?: throw IllegalStateException("No state found for state class: ${stateClass.qualifiedName}")
    }

    public suspend inline fun <reified S : ModuleState> selectState(): StateFlow<S> = selectState(S::class)

    public inline fun <reified S : ModuleState> selectStateNonSuspend(): StateFlow<S> = selectStateNonSuspend(S::class)

    @Suppress("UNCHECKED_CAST")
    override suspend fun <L : ModuleLogic> selectLogic(logicClass: KClass<L>): L {
        initialized.first { it }
        return info(logicClass)?.logic?.load() as? L
            ?: throw IllegalStateException("No logic found for logic class: $logicClass")
    }

    override fun <M : Any> getModule(moduleClass: KClass<M>): M? {
        @Suppress("UNCHECKED_CAST")
        return modules.firstOrNull { moduleClass.isInstance(it) } as M?
    }

    override fun getRegisteredModules(): List<Module<*, *>> = modules.toList()

    override fun getStateFlowForModule(module: Module<*, *>): StateFlow<ModuleState>? =
        info(module::class)?.state?.asStateFlow()

    override suspend fun getLogicForModule(module: Module<*, *>): ModuleLogic? {
        initialized.first { it }
        return info(module::class)?.logic?.load()
    }

    public suspend inline fun <reified L : ModuleLogic> selectLogic(): L = selectLogic(L::class)

    public fun cleanup() {
        highPriorityChannel.close()
        lowPriorityChannel.close()
        failPending(highPriorityChannel)
        failPending(lowPriorityChannel)
        crashScope.cancel()
        coroutineScope.cancel()
    }

    private fun failPending(channel: Channel<DispatchEnvelope>) {
        val closed = IllegalStateException("Store is closed")
        while (true) {
            val envelope = channel.tryReceive().getOrNull() ?: return
            envelope.completion?.complete(DispatchResult.Error(closed))
        }
    }

    public suspend fun saveState(state: Map<String, ModuleState>) {
        persistenceManager?.persistState(state) ?: throw IllegalStateException("No persistence strategy set")
    }

    public suspend fun loadState() {
        val restoredState = persistenceManager?.restoreState()
        if (restoredState == null) {
            ReaktivDebug.warn("No persistence strategy set when using loadState")
        }
        restoredState?.forEach { (key, state) ->
            applyState(key, state, "Persistence")
        }
    }

    public suspend fun hasPersistedState(): Boolean = persistenceManager?.hasPersistedState() ?: false

    public companion object {
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
