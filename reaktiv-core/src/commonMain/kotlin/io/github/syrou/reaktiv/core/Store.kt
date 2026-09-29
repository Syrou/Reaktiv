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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
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
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import kotlinx.serialization.modules.SerializersModule
import kotlin.coroutines.CoroutineContext
import kotlin.reflect.KClass

@OptIn(ExperimentalReaktivApi::class)
@Suppress("DEPRECATION")
public class Store internal constructor(
    private val coroutineScope: CoroutineScope,
    private val middlewares: List<Middleware>,
    @PublishedApi
    internal val modules: List<Module<ModuleState, ModuleAction>>,
    private val persistenceManager: PersistenceManager?,
    override val serializersModule: SerializersModule,
    private val externalStateGranted: Boolean,
) : StoreAccessor(coroutineScope), InternalStoreOperations {
    private val resetMutex = Mutex()
    private val highPriorityChannel: Channel<DispatchEnvelope> = Channel(Channel.UNLIMITED, onUndeliveredElement = ::failClosed)
    private val lowPriorityChannel: Channel<DispatchEnvelope> = Channel(Channel.UNLIMITED, onUndeliveredElement = ::failClosed)

    private val moduleInfos: List<ModuleInfo> =
        modules.map { module -> ModuleInfo(module, MutableStateFlow(module.initialState)) }

    private val moduleInfo: Map<KClass<*>, ModuleInfo> = buildMap {
        moduleInfos.forEach { info ->
            put(info.module::class, info)
            put(info.module.initialState::class, info)
        }
    }

    private val namedModuleInfo: Map<String, ModuleInfo> = buildMap {
        moduleInfos.forEach { info ->
            info.module::class.qualifiedName?.let { put(it, info) }
            info.module.initialState::class.qualifiedName?.let { put(it, info) }
        }
    }

    private val logicIndex = AtomicReference<Map<KClass<*>, ModuleInfo>>(emptyMap())

    private fun info(key: KClass<*>): ModuleInfo? = moduleInfo[key] ?: logicIndex.load()[key]

    private val _initialized: MutableStateFlow<Boolean> = MutableStateFlow(false)
    public val initialized: StateFlow<Boolean> = _initialized.asStateFlow()

    private val constructed = CompletableDeferred<Unit>()
    private val initFailure = AtomicReference<Throwable?>(null)
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

    private val generation = AtomicReference<Job>(SupervisorJob(storeJob))

    private val resetEpoch = AtomicLong(0L)

    private val pipelineJob: Job = SupervisorJob(coroutineScope.coroutineContext[Job])

    private val dispatchEnqueuedCount = AtomicLong(0L)
    private val dispatchProcessedCount = AtomicLong(0L)

    private val externalControlMutex = Mutex()
    private val externallyDriven = AtomicBoolean(false)
    private val externalStateDeniedWarned = AtomicBoolean(false)
    private val dispatchInstrumentation = AtomicReference<DispatchInstrumentation?>(null)

    private val externalStateAccess = object : ExternalStateAccess {
        override val isUnderControl: Boolean
            get() = externallyDriven.load()

        override suspend fun hydrate(states: Map<String, ModuleState>, source: HydrateSource): DispatchResult =
            dispatchAndAwait(StoreAction.Hydrate(states, source))

        override suspend fun beginControl(): Unit = externalControlMutex.withLock {
            if (externallyDriven.load()) return@withLock
            notifyExternalControl(true)
            externallyDriven.store(true)
            traceExternalControl(true)
        }

        override suspend fun endControl(): Unit = externalControlMutex.withLock {
            if (!externallyDriven.load()) return@withLock
            externallyDriven.store(false)
            traceExternalControl(false)
            notifyExternalControl(false)
        }
    }

    override fun externalState(): ExternalStateAccess? = externalStateAccess.takeIf { externalStateGranted }

    /**
     * `true` while a remote publisher authors this store's state.
     *
     * @see InternalStoreOperations.beginExternalControl
     */
    public val isExternallyDriven: Boolean
        get() = externallyDriven.load()

    @Deprecated(
        "Implement ExternalStateRequester on a module and return true from startsUnderExternalControl().",
        level = DeprecationLevel.WARNING
    )
    @ExperimentalReaktivApi
    override fun markExternallyDriven() {
        if (grantedOrWarn("markExternallyDriven")) externallyDriven.store(true)
    }

    override val coroutineContext: CoroutineContext
        get() = baseContext + generation.load()

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
            enqueuedAtMs = if (instrumentationActive()) currentTimeMillis() else 0L,
            epoch = resetEpoch.load()
        )
        if (target.trySend(envelope).isFailure) {
            throw IllegalStateException("Store is closed")
        }
        dispatchEnqueuedCount.addAndFetch(1L)
    }

    override val dispatch: Dispatch = { action -> enqueue(action, completion = null) }

    override suspend fun dispatchAndAwait(action: ModuleAction): DispatchResult {
        if (currentCoroutineContext()[PipelineMarker] != null) {
            throw IllegalStateException(
                "dispatchAndAwait() cannot be called from inside the dispatch pipeline, because the " +
                    "pipeline would be waiting for itself. Use dispatch(), or launch a coroutine on the store."
            )
        }
        val completion = CompletableDeferred<DispatchResult>()
        enqueue(action, completion)
        return completion.await()
    }

    private fun initializeModules() {
        try {
            moduleInfos.forEach { info -> info.state.update { info.module.initialState } }
            if (externalStateGranted &&
                modules.any { (it as? ExternalStateRequester)?.startsUnderExternalControl() == true }
            ) {
                externallyDriven.store(true)
            }
            val logicKeys = mutableMapOf<KClass<*>, ModuleInfo>()
            moduleInfos.forEach { info ->
                val logic = info.module.createLogic(this)
                info.logic.store(logic)
                logicKeys[logic::class] = info
            }
            logicIndex.store(logicKeys)
            initFailure.store(null)
            constructed.complete(Unit)
        } catch (e: Throwable) {
            initFailure.store(e)
            throw e
        } finally {
            _initialized.update { true }
        }
    }

    private fun failConstruction(cause: Throwable) {
        constructed.completeExceptionally(cause)
        highPriorityChannel.cancel()
        lowPriorityChannel.cancel()
    }

    override suspend fun reset(): Boolean {
        if (!constructed.isCompleted) {
            throw IllegalArgumentException("Reset can not be called until the Store has been constructed!")
        }
        if (currentCoroutineContext()[PipelineMarker] != null) {
            throw IllegalStateException(
                "reset() cannot be awaited from inside the dispatch pipeline, because the pipeline " +
                    "is what completes it. Call resetAsync() from middleware instead."
            )
        }

        if (!resetMutex.tryLock()) {
            return false
        }

        val requester = currentCoroutineContext()[Job]
        return withContext(NonCancellable) {
            try {
                _initialized.update { false }
                externallyDriven.store(false)
                val retired = generation.exchange(SupervisorJob(storeJob))
                drain(retire(retired, requester))
                awaitResetFence()
                true
            } finally {
                resetMutex.unlock()
            }
        }
    }

    private fun retire(retired: Job, requester: Job?): List<Job> {
        val cause = CancellationException("Store Reset")
        if (requester == null || !retired.isAncestorOf(requester)) {
            val children = retired.children.toList()
            retired.cancel(cause)
            return children
        }
        val cancelled = mutableListOf<Job>()
        var node: Job = retired
        var requesterBranch: Job? = null
        while (node !== requester) {
            val next = node.children.first { it === requester || it.isAncestorOf(requester) }
            if (requesterBranch == null) requesterBranch = next
            node.children.forEach { child ->
                if (child !== next) {
                    child.cancel(cause)
                    cancelled += child
                }
            }
            node = next
        }
        requesterBranch?.invokeOnCompletion { retired.cancel(cause) }
        return cancelled
    }

    private fun Job.isAncestorOf(target: Job): Boolean =
        children.any { it === target || it.isAncestorOf(target) }

    private suspend fun drain(cancelled: List<Job>) {
        if (cancelled.isEmpty()) return
        val finished = withTimeoutOrNull(RESET_DRAIN_TIMEOUT_MS) {
            cancelled.forEach { it.join() }
        }
        if (finished == null) {
            val remaining = cancelled.count { !it.isCompleted }
            ReaktivDebug.warn(
                "Store reset: $remaining coroutine(s) were still running ${RESET_DRAIN_TIMEOUT_MS}ms " +
                    "after being cancelled, continuing without them"
            )
        }
    }

    private suspend fun runBeforeReset(): Throwable? {
        var failure: Throwable? = null
        moduleInfos.forEach { info ->
            try {
                info.logic.load()?.beforeReset()
            } catch (e: Exception) {
                ReaktivDebug.warn(
                    "Store reset: beforeReset failed for ${info.module::class.simpleName} - ${e.message}"
                )
                if (failure == null) failure = e
            }
        }
        return failure
    }

    private suspend fun awaitResetFence() {
        val completion = CompletableDeferred<DispatchResult>()
        resetEpoch.addAndFetch(1L)
        enqueue(ResetFence, completion)
        val result = completion.await()
        if (result is DispatchResult.Error) throw result.cause
    }

    override fun resetAsync(): Job = CoroutineScope(baseContext + storeJob).launch {
        reset()
    }

    private suspend fun processActionChannel() {
        try {
            constructed.await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            return
        }
        var appliedEpoch = 0L
        val heldForNextGeneration = mutableListOf<DispatchEnvelope>()
        try {
            while (true) {
                val envelope = highPriorityChannel.tryReceive().getOrNull()
                    ?: select {
                        highPriorityChannel.onReceiveCatching { it.getOrNull() }
                        lowPriorityChannel.onReceiveCatching { it.getOrNull() }
                    }
                    ?: return
                when {
                    envelope.epoch < appliedEpoch -> dropEnvelope(envelope, DispatchDropReason.RESET)
                    envelope.action is CloseFence -> {
                        envelope.completion?.complete(
                            runBeforeReset()?.let { DispatchResult.Error(it) } ?: DispatchResult.Processed
                        )
                        dispatchProcessedCount.addAndFetch(1L)
                    }
                    envelope.action is ResetFence -> {
                        processResetFence(envelope)
                        appliedEpoch = envelope.epoch
                        val released = heldForNextGeneration.toList()
                        heldForNextGeneration.clear()
                        released.forEach { processEnvelope(it) }
                    }
                    envelope.epoch > appliedEpoch -> heldForNextGeneration += envelope
                    else -> processEnvelope(envelope)
                }
                yield()
            }
        } finally {
            heldForNextGeneration.forEach(::failClosed)
        }
    }

    private suspend fun processResetFence(envelope: DispatchEnvelope) {
        val cleanupFailure = runBeforeReset()
        val result = try {
            initializeModules()
            cleanupFailure?.let { DispatchResult.Error(it) } ?: DispatchResult.Processed
        } catch (e: Throwable) {
            DispatchResult.Error(e)
        }
        envelope.completion?.complete(result)
        dispatchProcessedCount.addAndFetch(1L)
    }

    private suspend fun dropEnvelope(envelope: DispatchEnvelope, reason: DispatchDropReason) {
        activeDispatchInstrumentation?.let { instrumentation ->
            guardInstrumentation("onDispatchDropped") { instrumentation.onDispatchDropped(envelope.action, reason) }
        }
        envelope.completion?.complete(DispatchResult.Dropped(reason))
        dispatchProcessedCount.addAndFetch(1L)
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
     * val instrumentation = storeAccessor.activeDispatchInstrumentation
     *     ?: return evaluate()
     * val token = instrumentation.onEvaluationStarted("MyScope", "evaluate", emptyMap())
     * ```
     *
     * @see setDispatchInstrumentation to install one
     */
    override val activeDispatchInstrumentation: DispatchInstrumentation?
        get() = dispatchInstrumentation.load()?.takeIf { it.isActive }

    private fun instrumentationActive(): Boolean = activeDispatchInstrumentation != null

    /**
     * Installs the instrumentation that observes this store, or `null` to remove it.
     *
     * @param instrumentation The implementation to install, replacing any previous one
     * @see activeDispatchInstrumentation
     */
    override fun setDispatchInstrumentation(instrumentation: DispatchInstrumentation?) {
        dispatchInstrumentation.store(instrumentation)
    }

    private suspend fun processEnvelope(envelope: DispatchEnvelope) {
        dropReason(envelope.action)?.let { reason ->
            dropEnvelope(envelope, reason)
            return
        }
        val instrumentation = activeDispatchInstrumentation
        var token = ""
        var processStartMs = 0L
        if (instrumentation != null) {
            processStartMs = currentTimeMillis()
            val queueWaitMs = if (envelope.enqueuedAtMs > 0L) {
                (processStartMs - envelope.enqueuedAtMs).coerceAtLeast(0L)
            } else 0L
            val queueDepth = (dispatchEnqueuedCount.load() - dispatchProcessedCount.load())
                .coerceAtLeast(1L)
            token = guardInstrumentation("onDispatchStarted") {
                instrumentation.onDispatchStarted(envelope.action, queueWaitMs, queueDepth)
            } ?: ""
        }
        try {
            val wasApplied = processAction(envelope.action, instrumentation)
            if (token.isNotEmpty()) {
                guardInstrumentation("onDispatchCompleted") {
                    instrumentation?.onDispatchCompleted(token, wasApplied, currentTimeMillis() - processStartMs)
                }
            }
            envelope.completion?.complete(
                if (wasApplied) DispatchResult.Processed else DispatchResult.Blocked
            )
        } catch (e: Throwable) {
            if (token.isNotEmpty()) {
                guardInstrumentation("onDispatchFailed") {
                    instrumentation?.onDispatchFailed(token, e, currentTimeMillis() - processStartMs)
                }
            }
            envelope.completion?.complete(DispatchResult.Error(e))
        } finally {
            dispatchProcessedCount.addAndFetch(1L)
        }
    }

    private fun dropReason(action: ModuleAction): DispatchDropReason? = when {
        action is StoreAction.Hydrate && action.source != HydrateSource.Restore &&
            !grantedOrWarn("StoreAction.Hydrate from '${action.origin}'") ->
            DispatchDropReason.EXTERNAL_STATE_DENIED
        externallyDriven.load() && action !is ExternalControlExempt -> DispatchDropReason.EXTERNAL_CONTROL
        else -> null
    }

    private fun grantedOrWarn(what: String): Boolean {
        if (externalStateGranted) return true
        if (!externalStateDeniedWarned.exchange(true)) {
            ReaktivDebug.warn(
                "Store: ignored $what because this store does not accept outside state. Install a module " +
                    "that requests it, such as the tooling module, or add " +
                    "externalState(ExternalStatePolicy.Allow) to createStore { }."
            )
        }
        return false
    }

    /**
     * Process an action through the middleware chain.
     * @return true if the action was applied to state, false if blocked by middleware
     */
    private suspend fun processAction(
        action: ModuleAction,
        instrumentation: DispatchInstrumentation?
    ): Boolean {
        if (action is StoreAction) {
            applyStoreAction(action)
            return true
        }
        val decorator = instrumentation?.let { guardInstrumentation("newDispatchDecorator") { it.newDispatchDecorator() } }
        val chain = if (decorator == null) plainChain else createMiddlewareChain(decorator)
        appliedInDispatch = false
        chain(action)
        return appliedInDispatch
    }

    private var appliedInDispatch = false

    private val reduce: suspend (ModuleAction) -> Unit = { action ->
        val info = info(action.moduleTag) ?: throw IllegalArgumentException(
            "No module found for action: ${action::class}"
        )

        @Suppress("UNCHECKED_CAST")
        val reducer = info.module.reducer as (ModuleState, ModuleAction) -> ModuleState
        info.state.update { current -> reducer(current, action) }
        appliedInDispatch = true
    }

    private val plainChain: suspend (ModuleAction) -> Unit by lazy { createMiddlewareChain(null) }

    private fun createMiddlewareChain(decorator: DispatchStepDecorator?): suspend (ModuleAction) -> Unit {
        val innermost = decorator?.let { guardInstrumentation("decorate") { it.decorate("reducer", reduce) } }
            ?: reduce
        return middlewares.foldRightIndexed(innermost) { index, middleware, next ->
            val step: suspend (ModuleAction) -> Unit = { action ->
                middleware(action, { getAllStates() }, this) { passedOn ->
                    next(passedOn)
                    info(passedOn.moduleTag)?.state?.value
                        ?: throw IllegalStateException("No state found for module: ${passedOn.moduleTag}")
                }
            }
            if (decorator == null) {
                step
            } else {
                val simpleName = middleware::class.simpleName?.takeIf { it.isNotBlank() } ?: "middleware"
                guardInstrumentation("decorate") { decorator.decorate("$simpleName[$index]", step) } ?: step
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
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ReaktivDebug.warn("Store: a crash listener failed - ${e.message}")
            }
        }
        return recovery
    }

    private inline fun <T> guardInstrumentation(hook: String, block: () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        ReaktivDebug.warn("Store: dispatch instrumentation failed in $hook - ${e.message}")
        null
    }

    private suspend fun applyStoreAction(action: StoreAction) {
        when (action) {
            is StoreAction.Hydrate -> {
                val hydrated = action.states.mapNotNull { (name, state) -> applyState(name, state, action.origin) }
                hydrated.distinct().forEach { info -> notifyHydrated(info, action.source) }
            }
        }
    }

    private fun applyState(stateClassName: String, newState: ModuleState, source: String): ModuleInfo? {
        val info = namedModuleInfo[stateClassName]
        return when {
            info == null -> {
                ReaktivDebug.warn("$source: Cannot apply state for unknown module: $stateClassName")
                null
            }

            info.state.value::class != newState::class -> {
                ReaktivDebug.warn(
                    "$source: State type mismatch for $stateClassName - " +
                        "expected ${info.state.value::class.simpleName}, got ${newState::class.simpleName}"
                )
                null
            }

            else -> {
                info.state.value = newState
                info
            }
        }
    }

    private suspend fun notifyHydrated(info: ModuleInfo, source: HydrateSource) {
        try {
            info.logic.load()?.onHydrated(source)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ReaktivDebug.warn("Store: onHydrated failed for ${info.module::class.simpleName} - ${e.message}")
        }
    }

    override fun getAllStates(): Map<String, ModuleState> =
        moduleInfos.associate { it.module.initialState::class.qualifiedName!! to it.state.value }

    @Deprecated(
        "Use externalState()?.hydrate(states, HydrateSource.External(\"DevTools\")), which exists only " +
            "when the store grants outside state.",
        ReplaceWith("externalState()?.hydrate(states, HydrateSource.External(\"DevTools\"))"),
        DeprecationLevel.WARNING
    )
    @ExperimentalReaktivApi
    override suspend fun applyExternalStates(states: Map<String, ModuleState>) {
        dispatchAndAwait(StoreAction.Hydrate(states, HydrateSource.External("DevTools")))
    }

    @Deprecated(
        "Use externalState()?.beginControl(), which exists only when the store grants outside state.",
        ReplaceWith("externalState()?.beginControl()"),
        DeprecationLevel.WARNING
    )
    @ExperimentalReaktivApi
    override suspend fun beginExternalControl() {
        if (grantedOrWarn("beginExternalControl")) externalStateAccess.beginControl()
    }

    @Deprecated(
        "Use externalState()?.endControl(), which exists only when the store grants outside state.",
        ReplaceWith("externalState()?.endControl()"),
        DeprecationLevel.WARNING
    )
    @ExperimentalReaktivApi
    override suspend fun endExternalControl() {
        externalStateAccess.endControl()
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
        constructed.await()
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
        awaitLogic()
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
        awaitLogic()
        return info(module::class)?.logic?.load()
    }

    private suspend fun awaitLogic() {
        if (currentCoroutineContext()[PipelineMarker] != null) {
            constructed.await()
        } else {
            initialized.first { it }
        }
        initFailure.load()?.let { cause ->
            throw IllegalStateException("The store could not create its module logic: ${cause.message}", cause)
        }
    }

    public suspend inline fun <reified L : ModuleLogic> selectLogic(): L = selectLogic(L::class)

    public suspend fun close() {
        if (currentCoroutineContext()[PipelineMarker] != null) {
            throw IllegalStateException(
                "close() cannot be awaited from inside the dispatch pipeline, because the pipeline runs it."
            )
        }
        withContext(NonCancellable) {
            if (constructed.isCompleted && initFailure.load() == null) {
                val completion = CompletableDeferred<DispatchResult>()
                runCatching { enqueue(CloseFence, completion) }.onSuccess {
                    withTimeoutOrNull(RESET_DRAIN_TIMEOUT_MS) { completion.await() }
                }
            }
            cleanup()
        }
    }

    public fun cleanup() {
        highPriorityChannel.cancel()
        lowPriorityChannel.cancel()
        crashScope.cancel()
        coroutineScope.cancel()
    }

    private fun failClosed(envelope: DispatchEnvelope) {
        envelope.completion?.complete(DispatchResult.Error(IllegalStateException("Store is closed")))
    }

    public suspend fun saveState(state: Map<String, ModuleState>) {
        persistenceManager?.persistState(state) ?: throw IllegalStateException("No persistence strategy set")
    }

    public suspend fun loadState() {
        val restoredState = persistenceManager?.restoreState()
        if (restoredState == null) {
            ReaktivDebug.warn("No persistence strategy set when using loadState")
        }
        if (restoredState != null) {
            dispatchAndAwait(StoreAction.Hydrate(restoredState, HydrateSource.Restore))
        }
    }

    public suspend fun hasPersistedState(): Boolean = persistenceManager?.hasPersistedState() ?: false

    init {
        launch {
            try {
                initializeModules()
            } catch (e: Throwable) {
                failConstruction(e)
                throw e
            }
        }
        CoroutineScope(baseContext + pipelineJob + PipelineMarker).launch { processActionChannel() }
    }

    private data object ResetFence : ModuleAction(Store::class), HighPriorityAction, ExternalControlExempt

    private data object CloseFence : ModuleAction(Store::class), HighPriorityAction, ExternalControlExempt

    private object PipelineMarker : CoroutineContext.Element, CoroutineContext.Key<PipelineMarker> {
        override val key: CoroutineContext.Key<*>
            get() = this
    }

    public companion object {
        private const val RESET_DRAIN_TIMEOUT_MS: Long = 5_000L
    }
}
