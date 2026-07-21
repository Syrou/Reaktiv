package io.github.syrou.reaktiv.devtools.service

import io.github.syrou.reaktiv.core.ExperimentalReaktivApi
import io.github.syrou.reaktiv.core.Middleware
import io.github.syrou.reaktiv.core.ModuleState
import io.github.syrou.reaktiv.core.Store
import io.github.syrou.reaktiv.core.tracing.LogicTracer
import io.github.syrou.reaktiv.core.util.ReaktivDebug
import io.github.syrou.reaktiv.core.util.currentTimeMillis
import io.github.syrou.reaktiv.core.util.reaktivJson
import io.github.syrou.reaktiv.devtools.client.ConnectionState
import io.github.syrou.reaktiv.devtools.client.DevToolsConnection
import io.github.syrou.reaktiv.devtools.middleware.DevToolsConfig
import io.github.syrou.reaktiv.devtools.protocol.ClientRole
import io.github.syrou.reaktiv.devtools.protocol.DevToolsMessage
import io.github.syrou.reaktiv.devtools.tracing.DevToolsLogicObserver
import io.github.syrou.reaktiv.introspection.tooling.ServiceState
import io.github.syrou.reaktiv.introspection.tooling.ServiceStatus
import io.github.syrou.reaktiv.introspection.tooling.ToolingCommand
import io.github.syrou.reaktiv.introspection.tooling.ToolingService
import io.github.syrou.reaktiv.introspection.capture.SessionHistory
import io.github.syrou.reaktiv.introspection.capture.chunked
import io.github.syrou.reaktiv.introspection.protocol.CapturedAction
import io.github.syrou.reaktiv.introspection.protocol.DeltaKind
import io.github.syrou.reaktiv.introspection.protocol.mergeCapturedDeltas
import io.github.syrou.reaktiv.introspection.protocol.mergeFieldJson
import io.github.syrou.reaktiv.introspection.tooling.ToolingServiceContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.PolymorphicSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

@OptIn(ExperimentalReaktivApi::class)
public class DevToolsService(private val config: DevToolsConfig) : ToolingService {

    override val name: String = "devtools"

    private var context: ToolingServiceContext? = null
    private var connection: DevToolsConnection? = null
    private var currentRole: ClientRole = ClientRole.UNASSIGNED
    private var currentServerUrl: String? = config.serverUrl
    private var json: Json? = null
    private var logicObserver: DevToolsLogicObserver? = null
    private var clientId: String = ""
    private var getAllStatesRef: (suspend () -> Map<String, ModuleState>)? = null
    private var manuallyDisconnected: Boolean = false
    private var pendingReconnect: Boolean = false
    private var pendingReconnectRole: ClientRole? = null
    private var reconnectJob: Job? = null
    private var listenerHandshake: Job? = null
    private var firstProjection: CompletableDeferred<Unit>? = null

    /**
     * Set once a follower has given up waiting for state and been handed back to local control.
     *
     * A store reset restarts this service, which would otherwise re-request LISTENER from
     * [DevToolsConfig.defaultRole] and be gated again, resetting again ten seconds later. The
     * client stays connected and can still be told to follow explicitly through [follow].
     */
    private var listenerRoleAbandoned: Boolean = false

    private var listenerStartPending: Boolean =
        config.enabled && config.autoConnect && config.defaultRole == ClientRole.LISTENER

    /**
     * One-shot: only the very first store construction is gated ahead of logic.
     *
     * [io.github.syrou.reaktiv.introspection.tooling.ToolingLogic] is rebuilt on every store
     * reset, so a standing `true` here would re-gate the store during the reset that recovers
     * from a failed handshake and freeze it permanently. Later follower entries go through
     * [beginExternalControl] on role assignment instead.
     */
    override val startsExternallyDriven: Boolean
        get() = listenerStartPending

    override fun createMiddleware(): Middleware = { action, getAllStates, _, updatedState ->
        if (getAllStatesRef == null) {
            getAllStatesRef = getAllStates
        }
        updatedState(action)
    }

    override suspend fun onCommand(command: ToolingCommand, args: Map<String, String>) {
        if (command !is DevToolsCommand) return
        when (command) {
            DevToolsCommand.CONNECT -> {
                val url = args["url"] ?: config.serverUrl ?: return
                connect(url, args["role"]?.let { runCatching { ClientRole.valueOf(it) }.getOrNull() })
            }
            DevToolsCommand.DISCONNECT -> disconnect()
            DevToolsCommand.RECONNECT -> reconnect()
            DevToolsCommand.FOLLOW -> follow(args["publisher"])
            DevToolsCommand.UNFOLLOW -> unfollow()
        }
    }

    override suspend fun start(context: ToolingServiceContext) {
        this.context = context
        this.clientId = context.config.clientId
        this.json = (context.storeAccessor as? Store)?.serializersModule?.let { reaktivJson(it) }

        if (context.config.installLogicTracing) {
            val observer = DevToolsLogicObserver(
                clientId = clientId,
                scope = context.storeAccessor,
                isConnected = { isConnected() },
                sendMessage = { send(it) }
            )
            logicObserver = observer
            LogicTracer.addObserver(observer)
        }

        context.storeAccessor.launch {
            context.capture.actions.collect { event ->
                if (currentRole == ClientRole.PUBLISHER &&
                    config.allowActionCapture && config.allowStateCapture && isConnected()
                ) {
                    conflatedSend(context.storeAccessor, event)
                }
            }
        }
        context.storeAccessor.launch {
            context.capture.crashes.collect { crash ->
                if (isConnected()) {
                    try {
                        send(
                            DevToolsMessage.CrashReport(
                                clientId = clientId,
                                crash = crash,
                                sessionJson = context.capture.exportSession(crash)
                            )
                        )
                    } catch (e: Exception) {
                        ReaktivDebug.warn("DevTools: Failed to send crash report - ${e.message}")
                    }
                }
            }
        }
        context.storeAccessor.launch {
            context.capture.stateReads.collect { read ->
                if (currentRole == ClientRole.PUBLISHER && isConnected()) {
                    try {
                        send(DevToolsMessage.StateReadReport(clientId = clientId, read = read))
                    } catch (e: Exception) {
                        ReaktivDebug.warn("DevTools: Failed to send state read - ${e.message}")
                    }
                }
            }
        }

        if (listenerStartPending) {
            listenerStartPending = false
            armStartupGateBackstop()
        }

        if (pendingReconnect) {
            pendingReconnect = false
            manuallyDisconnected = false
            launchReconnectLoop(pendingReconnectRole)
        } else if (config.enabled && config.autoConnect && config.serverUrl != null) {
            connect(config.serverUrl, config.defaultRole)
        } else {
            context.setStatus(ServiceStatus(ServiceState.STOPPED, "awaiting connect"))
        }
    }

    override suspend fun stop() {
        listenerHandshake?.cancel()
        listenerHandshake = null
        disconnect()
        logicObserver?.let { LogicTracer.removeObserver(it) }
        logicObserver = null
    }

    public suspend fun connect(serverUrl: String, role: ClientRole? = config.defaultRole) {
        val context = context ?: return
        manuallyDisconnected = false
        connection?.disconnect()
        currentServerUrl = serverUrl
        context.setStatus(ServiceStatus(ServiceState.STARTING, "connecting to $serverUrl"))
        val newConnection = DevToolsConnection(serverUrl)
        connection = newConnection
        try {
            newConnection.connect(clientId, context.config.clientName, context.config.platform)
            if (!newConnection.isConnectedNow()) {
                throw IllegalStateException("connection to $serverUrl failed")
            }
            newConnection.observeMessages { message -> handleServerMessage(message) }
            context.setStatus(ServiceStatus(ServiceState.RUNNING, "connected to $serverUrl"))
            launchConnectionMonitor(newConnection)
            val effectiveRole = if (role == ClientRole.LISTENER && listenerRoleAbandoned) null else role
            if (effectiveRole != null) {
                requestRole(effectiveRole, null)
            }
        } catch (e: Exception) {
            context.setStatus(ServiceStatus(ServiceState.DEGRADED, e.message))
            ReaktivDebug.warn("DevTools: Failed to connect - ${e.message}")
            releaseExternalControl("connection failed: ${e.message}")
        }
    }

    public suspend fun disconnect() {
        manuallyDisconnected = true
        reconnectJob?.cancel()
        reconnectJob = null
        connection?.disconnect()
        connection = null
        currentRole = ClientRole.UNASSIGNED
        context?.setStatus(ServiceStatus(ServiceState.STOPPED, "disconnected"))
    }

    private fun DevToolsConnection.isConnectedNow(): Boolean =
        connectionState.value == ConnectionState.CONNECTED

    private fun launchConnectionMonitor(monitored: DevToolsConnection) {
        val context = context ?: return
        context.storeAccessor.launch {
            monitored.connectionState.first {
                it == ConnectionState.ERROR || it == ConnectionState.DISCONNECTED
            }
            if (connection !== monitored || manuallyDisconnected) return@launch
            handleConnectionLoss()
        }
    }

    private suspend fun handleConnectionLoss() {
        val context = context ?: return
        val previousRole = currentRole
        currentRole = ClientRole.UNASSIGNED
        context.setStatus(ServiceStatus(ServiceState.DEGRADED, "connection lost"))
        ReaktivDebug.warn("DevTools: Connection lost (was $previousRole)")
        val roleToRequest = if (previousRole == ClientRole.PUBLISHER) ClientRole.PUBLISHER else null
        if (previousRole == ClientRole.LISTENER) {
            if (config.autoReconnect) {
                pendingReconnect = true
                pendingReconnectRole = null
            }
            endExternalControl()
            (context.storeAccessor as? Store)?.resetAsync()
        } else if (config.autoReconnect) {
            launchReconnectLoop(roleToRequest)
        }
    }

    private fun launchReconnectLoop(roleToRequest: ClientRole?) {
        val context = context ?: return
        reconnectJob?.cancel()
        reconnectJob = context.storeAccessor.launch {
            var delayMs = RECONNECT_INITIAL_DELAY_MS
            while (!manuallyDisconnected && !isConnected()) {
                context.setStatus(ServiceStatus(ServiceState.STARTING, "reconnecting in ${delayMs / 1000}s"))
                delay(delayMs)
                if (manuallyDisconnected) break
                val url = currentServerUrl ?: break
                connect(url, roleToRequest)
                delayMs = (delayMs * 2).coerceAtMost(RECONNECT_MAX_DELAY_MS)
            }
        }
    }

    public suspend fun reconnect() {
        currentServerUrl?.let { connect(it) }
    }

    public suspend fun follow(publisherClientId: String? = null) {
        listenerRoleAbandoned = false
        requestRole(ClientRole.LISTENER, publisherClientId)
    }

    public suspend fun unfollow() {
        requestRole(ClientRole.UNASSIGNED, null)
        val wasFollowing = currentRole == ClientRole.LISTENER
        currentRole = ClientRole.UNASSIGNED
        context?.setStatus(ServiceStatus(ServiceState.RUNNING, "connected"))
        if (wasFollowing) {
            endExternalControl()
            (context?.storeAccessor as? Store)?.resetAsync()
        }
    }

    public fun isConnected(): Boolean =
        connection?.connectionState?.value == ConnectionState.CONNECTED

    /**
     * Enters external control and waits on the first projection rather than on the clock.
     *
     * Holding the LISTENER role is not evidence that state will arrive, so entering the gate
     * arms a wait that completes the moment a projection lands. The timeout is only a backstop
     * for a publisher that accepts the attachment and then sends nothing, which is the one case
     * with no event to await. Every case where the answer is already knowable, no publisher
     * assigned, a different role, or a failed connection, releases immediately through
     * [releaseExternalControl] without waiting at all.
     */
    private suspend fun beginExternalControl() {
        val context = context ?: return
        val gate = CompletableDeferred<Unit>()
        firstProjection = gate
        context.storeAccessor.asInternalOperations()?.beginExternalControl()
        listenerHandshake?.cancel()
        listenerHandshake = context.storeAccessor.launch {
            val arrived = withTimeoutOrNull(FIRST_PROJECTION_TIMEOUT_MS) { gate.await() }
            if (arrived == null) {
                releaseExternalControl("publisher sent no state")
            }
        }
    }

    /**
     * The body is [NonCancellable] because it is reached from inside [listenerHandshake] on the
     * backstop path and cancels that very job. Without it the release would abort at the first
     * suspension point and leave the store gated, which is the failure it exists to prevent.
     */
    private suspend fun endExternalControl(): Unit = withContext(NonCancellable) {
        listenerHandshake?.cancel()
        listenerHandshake = null
        firstProjection = null
        context?.storeAccessor?.asInternalOperations()?.endExternalControl()
    }

    /**
     * Releases a store that was gated at construction but has no publisher state to wait for.
     *
     * Armed only for the window between the construction-time gate and the server answering
     * with a role. Once a role arrives, [handleRoleAssignment] resolves the question
     * immediately and this backstop is superseded.
     */
    private fun armStartupGateBackstop() {
        val context = context ?: return
        listenerHandshake?.cancel()
        listenerHandshake = context.storeAccessor.launch {
            delay(FIRST_PROJECTION_TIMEOUT_MS)
            if (firstProjection == null) {
                releaseExternalControl("no role assignment from server")
            }
        }
    }

    /**
     * Hands a gated store back to local control and reboots it as an ordinary client.
     *
     * A store gated at construction has deliberately skipped its own start-up, so it has no
     * state of its own to fall back on and would otherwise render the bootstrap loading
     * placeholder forever. The reset lets it run its own start lambda and intercepts normally.
     *
     * The role is marked abandoned so that the restart, which starts this service again, does
     * not re-request LISTENER from [DevToolsConfig.defaultRole] and gate straight back into the
     * same wait. An explicit [follow] clears that.
     */
    private suspend fun releaseExternalControl(reason: String) {
        val context = context ?: return
        val store = context.storeAccessor as? Store ?: return
        if (!store.isExternallyDriven) return
        withContext(NonCancellable) {
            ReaktivDebug.warn("DevTools: Resuming local control ($reason)")
            listenerRoleAbandoned = true
            context.setStatus(
                ServiceStatus(ServiceState.DEGRADED, "resumed local control: $reason")
            )
            endExternalControl()
            store.resetAsync()
        }
    }

    public suspend fun send(message: DevToolsMessage) {
        connection?.send(message)
    }

    private val pendingDeltas = mutableMapOf<String, CapturedAction>()
    private val pendingDeltasMutex = Mutex()
    private var deltaFlushJob: Job? = null

    /**
     * Buffers per-module deltas for a short window so a burst collapses into one send.
     *
     * The buffer is shared between the capture collector and the flush coroutine, which run on
     * different threads, so both sides must hold [pendingDeltasMutex]. Draining without it can
     * lose any delta produced between reading the batch and clearing the map, which silently
     * desyncs the follower until the next full state sync.
     */
    private suspend fun conflatedSend(scope: CoroutineScope, event: CapturedAction) {
        pendingDeltasMutex.withLock {
            val pending = pendingDeltas[event.moduleName]
            pendingDeltas[event.moduleName] =
                if (pending != null) mergeCapturedDeltas(pending, event) else event
        }
        if (deltaFlushJob?.isActive != true) {
            deltaFlushJob = scope.launch {
                delay(DELTA_CONFLATION_WINDOW_MS)
                val batch = pendingDeltasMutex.withLock {
                    val drained = pendingDeltas.values.toList()
                    pendingDeltas.clear()
                    drained
                }
                batch.forEach { pending ->
                    try {
                        send(DevToolsMessage.ActionDispatched(pending))
                    } catch (e: Exception) {
                        ReaktivDebug.warn("DevTools: Failed to send action - ${e.message}")
                    }
                }
            }
        }
    }

    private suspend fun requestRole(role: ClientRole, publisherClientId: String?) {
        if (!isConnected()) {
            ReaktivDebug.warn("DevTools: Cannot request role - not connected")
            return
        }
        try {
            send(
                DevToolsMessage.RoleAssignment(
                    targetClientId = clientId,
                    role = role,
                    publisherClientId = publisherClientId
                )
            )
        } catch (e: Exception) {
            ReaktivDebug.warn("DevTools: Failed to request role - ${e.message}")
        }
    }

    private suspend fun handleServerMessage(message: DevToolsMessage) {
        when (message) {
            is DevToolsMessage.RoleAssignment -> {
                if (message.targetClientId == clientId) {
                    handleRoleAssignment(message)
                }
            }
            is DevToolsMessage.StateSync -> {
                if (currentRole == ClientRole.LISTENER) {
                    applyStateSync(message)
                }
            }
            is DevToolsMessage.ListenerAttached -> {
                if (currentRole == ClientRole.PUBLISHER) {
                    if (message.role == ClientRole.ORCHESTRATOR) {
                        sendSessionHistorySync()
                    } else {
                        sendFullStateSync()
                    }
                }
            }
            is DevToolsMessage.ActionDispatched -> {
                if (currentRole == ClientRole.LISTENER) {
                    applyActionDelta(message.event)
                }
            }
            else -> {}
        }
    }

    private suspend fun handleRoleAssignment(assignment: DevToolsMessage.RoleAssignment) {
        val previousRole = currentRole
        currentRole = assignment.role
        if (assignment.role == ClientRole.LISTENER) {
            if (assignment.publisherClientId == null) {
                releaseExternalControl("no publisher to follow")
            } else {
                beginExternalControl()
            }
        } else {
            releaseExternalControl("assigned role ${assignment.role}")
        }
        send(
            DevToolsMessage.RoleAcknowledgment(
                clientId = clientId,
                role = assignment.role,
                success = true,
                message = "Role changed to ${assignment.role}"
            )
        )
        val detail = when (assignment.role) {
            ClientRole.PUBLISHER -> "publishing"
            ClientRole.LISTENER -> "following ${assignment.publisherClientId ?: "current publisher"}"
            else -> "connected"
        }
        context?.setStatus(ServiceStatus(ServiceState.RUNNING, detail))
        if (assignment.role == ClientRole.PUBLISHER && previousRole != ClientRole.PUBLISHER) {
            sendSessionHistorySync()
        }
    }

    private suspend fun sendSessionHistorySync() {
        val capture = context?.capture ?: return
        if (!isConnected()) return
        try {
            val history = capture.getSessionHistory().withBaseline()
            val chunks = history.chunked()
            if (chunks.size == 1) {
                send(DevToolsMessage.SessionHistorySync(clientId, history))
            } else {
                chunks.forEachIndexed { index, chunk ->
                    send(DevToolsMessage.SessionHistoryChunk(clientId, index, chunks.size, chunk))
                }
            }
        } catch (e: Exception) {
            ReaktivDebug.warn("DevTools: Failed to send session history sync - ${e.message}")
        }
    }

    /**
     * Guarantees the history carries a full state baseline for the observer to build on.
     *
     * The capture only records an initial state on the first non-tooling action, so a publisher
     * that has not dispatched anything yet reports `{}`. An observer given that has nothing to
     * reconstruct from and can only ever display the modules that later appear in deltas. When
     * the baseline is missing no actions have been captured either, so substituting the current
     * state stays consistent with the delta stream that follows.
     */
    private suspend fun SessionHistory.withBaseline(): SessionHistory {
        if (initialStateJson.isNotBlank() && initialStateJson != "{}") return this
        val json = json ?: return this
        val states = getAllStatesRef?.invoke() ?: return this
        val mapSerializer = MapSerializer(String.serializer(), PolymorphicSerializer(ModuleState::class))
        return copy(initialStateJson = json.encodeToString(mapSerializer, states))
    }

    private suspend fun sendFullStateSync() {
        val context = context ?: return
        val json = json ?: return
        if (!isConnected()) return
        try {
            val allStates = getAllStatesRef?.invoke() ?: return
            val mapSerializer = MapSerializer(String.serializer(), PolymorphicSerializer(ModuleState::class))
            send(
                DevToolsMessage.StateSync(
                    fromClientId = clientId,
                    timestamp = currentTimeMillis(),
                    stateJson = json.encodeToString(mapSerializer, allStates)
                )
            )
        } catch (e: Exception) {
            ReaktivDebug.warn("DevTools: Failed to send full state sync - ${e.message}")
        }
    }

    private companion object {
        const val DELTA_CONFLATION_WINDOW_MS: Long = 75L
        const val RECONNECT_INITIAL_DELAY_MS: Long = 1000L
        const val RECONNECT_MAX_DELAY_MS: Long = 30_000L
        const val FIRST_PROJECTION_TIMEOUT_MS: Long = 10_000L
    }

    private val followerShadow = mutableMapOf<String, JsonObject>()

    private suspend fun applyActionDelta(event: CapturedAction) {
        val json = json ?: return
        try {
            val incoming = json.parseToJsonElement(event.stateDeltaJson).jsonObject
            val merged = if (event.deltaKind == DeltaKind.FIELDS) {
                val base = followerShadow[event.moduleName]
                if (base == null) {
                    ReaktivDebug.warn(
                        "DevTools: Dropped field delta for ${event.moduleName} with no base snapshot"
                    )
                    context?.setStatus(
                        ServiceStatus(
                            ServiceState.DEGRADED,
                            "desynced: field delta for ${event.moduleName} before first sync"
                        )
                    )
                    return
                }
                json.parseToJsonElement(
                    mergeFieldJson(base.toString(), event.stateDeltaJson)
                ).jsonObject
            } else {
                incoming
            }
            followerShadow[event.moduleName] = merged
            val state: ModuleState = json.decodeFromString(
                PolymorphicSerializer(ModuleState::class), merged.toString()
            )
            context?.storeAccessor?.asInternalOperations()?.applyExternalStates(mapOf(event.moduleName to state))
        } catch (e: Exception) {
            ReaktivDebug.warn("DevTools: Failed to apply action delta - ${e.message}")
            context?.setStatus(
                ServiceStatus(
                    ServiceState.DEGRADED,
                    "delta rejected for ${event.moduleName}: ${e.message}"
                )
            )
        }
    }

    private suspend fun applyStateSync(sync: DevToolsMessage.StateSync) {
        val storeAccessor = context?.storeAccessor ?: return
        val json = json ?: return
        try {
            if (sync.moduleName.isBlank()) {
                try {
                    val tree = json.parseToJsonElement(sync.stateJson).jsonObject
                    tree.forEach { (key, value) ->
                        (value as? JsonObject)?.let { followerShadow[key] = it }
                    }
                } catch (e: Exception) {
                    ReaktivDebug.warn("DevTools: Failed to update follower shadow - ${e.message}")
                }
            }
            val states: Map<String, ModuleState> = if (sync.moduleName.isNotBlank()) {
                mapOf(
                    sync.moduleName to json.decodeFromString(
                        PolymorphicSerializer(ModuleState::class), sync.stateJson
                    )
                )
            } else {
                json.decodeFromString(
                    MapSerializer(String.serializer(), PolymorphicSerializer(ModuleState::class)),
                    sync.stateJson
                )
            }
            storeAccessor.asInternalOperations()?.applyExternalStates(states)
            if (sync.moduleName.isBlank()) {
                onFirstProjectionApplied(states.keys)
            }
        } catch (e: Exception) {
            ReaktivDebug.warn("DevTools: Failed to apply state sync - ${e.message}")
            context?.setStatus(
                ServiceStatus(ServiceState.DEGRADED, "state sync rejected: ${e.message}")
            )
        }
    }

    /**
     * Records that replication actually started, which is what releases the recovery timer.
     *
     * A follower gated at construction has no state of its own to fall back on, so silence
     * here is indistinguishable from a hang. Reporting the applied module set makes a partial
     * projection (a follower built against a different set of modules or navigatables than
     * the publisher) visible in the debug menu instead of showing as a stuck loading screen.
     */
    private suspend fun onFirstProjectionApplied(appliedModules: Set<String>) {
        val gate = firstProjection ?: return
        if (gate.isCompleted) return
        gate.complete(Unit)
        context?.setStatus(
            ServiceStatus(ServiceState.RUNNING, "replicating ${appliedModules.size} modules")
        )
    }
}
