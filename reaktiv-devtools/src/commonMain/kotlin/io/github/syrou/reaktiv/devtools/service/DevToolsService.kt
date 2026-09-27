package io.github.syrou.reaktiv.devtools.service

import io.github.syrou.reaktiv.devtools.DevToolsInternalApi
import io.github.syrou.reaktiv.core.ExperimentalReaktivApi
import io.github.syrou.reaktiv.core.ExternalStateAccess
import io.github.syrou.reaktiv.core.HydrateSource
import io.github.syrou.reaktiv.core.ModuleState
import io.github.syrou.reaktiv.core.util.ReaktivDebug
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import io.github.syrou.reaktiv.core.util.currentTimeMillis
import io.github.syrou.reaktiv.devtools.client.ConnectionState
import io.github.syrou.reaktiv.devtools.client.DevToolsConnection
import io.github.syrou.reaktiv.devtools.middleware.DevToolsConfig
import io.github.syrou.reaktiv.devtools.protocol.ClientRole
import io.github.syrou.reaktiv.devtools.protocol.DevToolsMessage
import io.github.syrou.reaktiv.introspection.tooling.ServiceState
import io.github.syrou.reaktiv.introspection.tooling.ServiceStatus
import io.github.syrou.reaktiv.introspection.tooling.ToolingCommand
import io.github.syrou.reaktiv.introspection.tooling.ToolingService
import io.github.syrou.reaktiv.introspection.capture.CapturedLogicEvent
import io.github.syrou.reaktiv.introspection.capture.SessionHistory
import io.github.syrou.reaktiv.introspection.protocol.CrashDiagnosis
import io.github.syrou.reaktiv.introspection.protocol.CrashInfo
import kotlinx.coroutines.flow.Flow
import io.github.syrou.reaktiv.introspection.capture.chunked
import io.github.syrou.reaktiv.introspection.protocol.CapturedAction
import io.github.syrou.reaktiv.introspection.protocol.DeltaKind
import io.github.syrou.reaktiv.introspection.protocol.mergeFields
import io.github.syrou.reaktiv.introspection.WireBudget
import io.github.syrou.reaktiv.introspection.approximateWireBytes
import io.github.syrou.reaktiv.introspection.network.NetworkTap
import io.github.syrou.reaktiv.introspection.restoreRedactedModuleElement
import io.github.syrou.reaktiv.introspection.tooling.ToolingServiceContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.PolymorphicSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import io.github.syrou.reaktiv.introspection.encodeSessionPayload
import io.github.syrou.reaktiv.core.util.selectLogic
import io.github.syrou.reaktiv.introspection.tooling.ToolingLogic
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.jsonObject

@DevToolsInternalApi
public const val SESSION_EXPORT_REQUEST: String = "export"

@OptIn(ExperimentalReaktivApi::class)
public class DevToolsService(private val config: DevToolsConfig) : ToolingService {

    override val name: String = "devtools"

    private var context: ToolingServiceContext? = null

    private var serviceScope: CoroutineScope? = null
    private var connection: DevToolsConnection? = null
    private var currentRole: ClientRole = ClientRole.UNASSIGNED
    private var currentServerUrl: String? = config.serverUrl
    private var json: Json? = null
    private var clientId: String = ""
    private var manuallyDisconnected: Boolean = false
    private var pendingReconnect: Boolean = false
    private var reconnectJob: Job? = null
    private var followerGate: FollowerGate? = null

    private class FollowerGate(val firstProjection: CompletableDeferred<Unit>, val watch: Job?)

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

    override suspend fun onCommand(command: ToolingCommand, args: Map<String, String>) {
        when (val typed = DevToolsCommands.typed(command, args) ?: return) {
            is DevToolsCommands.Connect -> open(typed.url ?: config.serverUrl ?: return, typed.role)
            DevToolsCommands.Disconnect -> close()
            DevToolsCommands.Reconnect -> reopen()
            is DevToolsCommands.Follow -> startFollowing(typed.publisherClientId)
            DevToolsCommands.Unfollow -> stopFollowing()
        }
    }

    override suspend fun onRequest(request: String, payload: JsonElement): JsonElement? = when (request) {
        SESSION_EXPORT_REQUEST -> context?.capture?.exportSession()?.let { JsonPrimitive(encodeSessionPayload(it)) }
        else -> null
    }

    override suspend fun start(context: ToolingServiceContext) {
        this.context = context
        this.clientId = context.config.clientId
        this.json = context.capture.stateJson.takeIf { context.storeAccessor.serializersModule != null }

        serviceScope?.cancel()
        val scope = CoroutineScope(
            context.storeAccessor.coroutineContext +
                SupervisorJob(context.storeAccessor.coroutineContext[Job])
        )
        serviceScope = scope
        val capture = context.capture
        val conflator = DeltaConflator(scope, DELTA_CONFLATION_WINDOW_MS) { pending ->
            send(DevToolsMessage.ActionDispatched(pending))
        }

        scope.launch {
            capture.actions.collect { event ->
                if (publishing() && config.allowActionCapture && config.allowStateCapture) {
                    conflator.offer(event)
                }
            }
        }
        scope.launch {
            capture.crashes.collect { crash ->
                if (isConnected()) {
                    send(DevToolsMessage.CrashReport(clientId = clientId, crash = crash, diagnosis = diagnose(crash)))
                }
            }
        }
        scope.forwardEach(capture.stateReads) { DevToolsMessage.StateReadReport(clientId = clientId, read = it) }
        scope.forwardEach(capture.markers) { DevToolsMessage.MarkerAdded(clientId = clientId, marker = it) }
        scope.forwardEach(capture.logicEvents) { event ->
            when (event) {
                is CapturedLogicEvent.Started -> DevToolsMessage.LogicMethodStarted(clientId, event.event)
                is CapturedLogicEvent.Completed -> DevToolsMessage.LogicMethodCompleted(clientId, event.event)
                is CapturedLogicEvent.Failed -> DevToolsMessage.LogicMethodFailed(clientId, event.event)
            }
        }
        scope.forwardBatched(capture.logs, LOG_BUFFER, LOG_BATCH_LIMIT) {
            DevToolsMessage.LogBatch(clientId = clientId, entries = it)
        }
        scope.forwardBatched(
            capture.network,
            NETWORK_BUFFER,
            NETWORK_BATCH_LIMIT,
            weigh = { it.approximateWireBytes() }
        ) {
            DevToolsMessage.NetworkBatch(clientId = clientId, events = it)
        }

        if (listenerStartPending) {
            listenerStartPending = false
            report(ServiceStatus(ServiceState.STARTING, "waiting for a publisher"))
        }

        if (pendingReconnect) {
            pendingReconnect = false
            manuallyDisconnected = false
            launchReconnectLoop(null)
        } else if (config.enabled && config.autoConnect && config.serverUrl != null) {
            open(config.serverUrl, config.defaultRole)
        } else {
            report(ServiceStatus(ServiceState.STOPPED, "awaiting connect"))
        }
    }

    private fun serviceLaunch(block: suspend CoroutineScope.() -> Unit): Job? =
        serviceScope?.launch(block = block)

    private fun publishing(): Boolean = currentRole == ClientRole.PUBLISHER && isConnected()

    private suspend fun diagnose(crash: CrashInfo): CrashDiagnosis? = try {
        context?.capture?.diagnoseCrash(crash)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (e: Exception) {
        ReaktivDebug.warn("DevTools: Could not diagnose the crash - ${e.message}")
        null
    }

    private fun <T> CoroutineScope.forwardEach(source: Flow<T>, message: (T) -> DevToolsMessage): Job = launch {
        source.collect { if (publishing()) send(message(it)) }
    }

    /**
     * Sends a stream in batches, cut by count and by estimated payload size.
     *
     * The first record opens a short window so a burst leaves as one message, and an idle stream
     * sends nothing. [weigh] lets a record type that varies by orders of magnitude, such as a
     * network exchange carrying full bodies, close a batch early rather than relying on the count
     * alone. A record heavier than the whole budget still goes out on its own, since splitting it
     * here would break the message it belongs to.
     */
    private fun <T> CoroutineScope.forwardBatched(
        source: Flow<T>,
        capacity: Int,
        limit: Int,
        weigh: (T) -> Int = { 0 },
        message: (List<T>) -> DevToolsMessage
    ) {
        val buffer = Channel<T>(capacity, BufferOverflow.DROP_OLDEST)
        launch { source.collect { if (publishing()) buffer.send(it) } }
        launch {
            while (true) {
                val first = buffer.receive()
                delay(BATCH_WINDOW_MS)
                val batch = arrayListOf(first)
                var budget = WireBudget.MAX_PAYLOAD_BYTES - weigh(first)
                while (batch.size < limit && budget > 0) {
                    val next = buffer.tryReceive().getOrNull() ?: break
                    batch.add(next)
                    budget -= weigh(next)
                }
                send(message(batch))
            }
        }
    }

    override suspend fun stop() {
        reconnectJob = null
        serviceScope?.cancel()
        serviceScope = null
        close()
        followerShadow.clear()
    }

    @Deprecated(CONTROL_BY_COMMAND, ReplaceWith("store.dispatch(DevToolsCommands.connect(serverUrl, role))"))
    public suspend fun connect(serverUrl: String, role: ClientRole? = config.defaultRole): Unit = open(serverUrl, role)

    @Deprecated(CONTROL_BY_COMMAND, ReplaceWith("store.dispatch(DevToolsCommands.disconnect())"))
    public suspend fun disconnect(): Unit = close()

    @Deprecated(CONTROL_BY_COMMAND, ReplaceWith("store.dispatch(DevToolsCommands.reconnect())"))
    public suspend fun reconnect(): Unit = reopen()

    @Deprecated(CONTROL_BY_COMMAND, ReplaceWith("store.dispatch(DevToolsCommands.follow(publisherClientId))"))
    public suspend fun follow(publisherClientId: String? = null): Unit = startFollowing(publisherClientId)

    @Deprecated(CONTROL_BY_COMMAND, ReplaceWith("store.dispatch(DevToolsCommands.unfollow())"))
    public suspend fun unfollow(): Unit = stopFollowing()

    private suspend fun open(serverUrl: String, role: ClientRole?) {
        val context = context ?: return
        manuallyDisconnected = false
        connection?.disconnect()
        currentServerUrl = serverUrl
        report(ServiceStatus(ServiceState.STARTING, "connecting to $serverUrl"))
        val newConnection = DevToolsConnection(serverUrl)
        connection = newConnection
        newConnection.observeMessages { message -> handleServerMessage(message) }
        try {
            newConnection.connect(clientId, context.config.clientName, context.config.platform)
            if (!newConnection.isConnectedNow()) {
                throw IllegalStateException("connection to $serverUrl failed")
            }
            report(ServiceStatus(ServiceState.RUNNING, "connected to $serverUrl"))
            launchConnectionMonitor(newConnection)
            val effectiveRole = when {
                role != ClientRole.LISTENER -> role
                listenerRoleAbandoned -> null
                externalState() == null -> null.also { refuseToFollow() }
                else -> role
            }
            if (effectiveRole != null) {
                requestRole(effectiveRole, null)
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (e: Exception) {
            report(ServiceStatus(ServiceState.DEGRADED, e.message))
            ReaktivDebug.warn("DevTools: Failed to connect - ${e.message}")
            releaseExternalControl("connection failed: ${e.message}")
        }
    }

    private suspend fun close() {
        manuallyDisconnected = true
        reconnectJob?.cancel()
        reconnectJob = null
        connection?.disconnect()
        connection = null
        currentRole = ClientRole.UNASSIGNED
        report(ServiceStatus(ServiceState.STOPPED, "disconnected"))
    }

    private fun DevToolsConnection.isConnectedNow(): Boolean =
        connectionState.value == ConnectionState.CONNECTED

    private fun launchConnectionMonitor(monitored: DevToolsConnection) {
        serviceLaunch {
            monitored.connectionState.first {
                it == ConnectionState.ERROR || it == ConnectionState.DISCONNECTED
            }
            if (connection === monitored && !manuallyDisconnected) {
                handleConnectionLoss()
            }
        }
    }

    private suspend fun handleConnectionLoss() {
        val context = context ?: return
        val previousRole = currentRole
        currentRole = ClientRole.UNASSIGNED
        report(ServiceStatus(ServiceState.DEGRADED, "connection lost"))
        ReaktivDebug.warn("DevTools: Connection lost (was $previousRole)")
        val roleToRequest = if (previousRole == ClientRole.PUBLISHER) ClientRole.PUBLISHER else null
        if (previousRole == ClientRole.LISTENER) {
            if (config.autoReconnect) {
                pendingReconnect = true
            }
            endExternalControl()
            context.storeAccessor.resetAsync()
        } else if (config.autoReconnect) {
            launchReconnectLoop(roleToRequest)
        }
    }

    private fun launchReconnectLoop(roleToRequest: ClientRole?) {
        val context = context ?: return
        reconnectJob?.cancel()
        reconnectJob = serviceLaunch {
            var delayMs = RECONNECT_INITIAL_DELAY_MS
            while (!manuallyDisconnected && !isConnected()) {
                report(ServiceStatus(ServiceState.STARTING, "reconnecting in ${delayMs / 1000}s"))
                delay(delayMs)
                if (manuallyDisconnected) break
                val url = currentServerUrl ?: break
                open(url, roleToRequest)
                delayMs = (delayMs * 2).coerceAtMost(RECONNECT_MAX_DELAY_MS)
            }
        }
    }

    private suspend fun reopen() {
        currentServerUrl?.let { open(it, config.defaultRole) }
    }

    private suspend fun startFollowing(publisherClientId: String?) {
        if (externalState() == null) {
            refuseToFollow()
            return
        }
        listenerRoleAbandoned = false
        requestRole(ClientRole.LISTENER, publisherClientId)
    }

    private fun externalState(): ExternalStateAccess? = context?.storeAccessor?.externalState()

    private suspend fun refuseToFollow() {
        listenerRoleAbandoned = true
        report(ServiceStatus(ServiceState.DEGRADED, FOLLOW_DENIED))
    }

    private suspend fun stopFollowing() {
        requestRole(ClientRole.UNASSIGNED, null)
        val wasFollowing = currentRole == ClientRole.LISTENER
        currentRole = ClientRole.UNASSIGNED
        report(ServiceStatus(ServiceState.RUNNING, "connected"))
        if (wasFollowing) {
            endExternalControl()
            context?.storeAccessor?.resetAsync()
        }
    }

    /**
     * Records a status locally and mirrors it to the server.
     *
     * A follower is otherwise invisible when something goes wrong: [ServiceStatus] only reaches
     * the app's own debug menu, and ReaktivDebug output is suppressed unless the host app called
     * enable(). Mirroring upstream puts the reason in the DevTools UI beside the client.
     */
    private suspend fun report(status: ServiceStatus) {
        context?.setStatus(status)
        if (isConnected()) send(DevToolsMessage.ClientStatus(clientId, status))
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
        val access = externalState() ?: return
        val firstProjection = CompletableDeferred<Unit>()
        followerGate?.watch?.cancel()
        followerGate = FollowerGate(firstProjection, null)
        access.beginControl()
        val watch = serviceLaunch {
            val arrived = withTimeoutOrNull(FIRST_PROJECTION_SLOW_MS) { firstProjection.await() }
            if (arrived == null) {
                ReaktivDebug.warn("DevTools: No publisher state yet, still waiting")
                report(ServiceStatus(ServiceState.DEGRADED, "no publisher state yet, still waiting"))
                firstProjection.await()
                report(ServiceStatus(ServiceState.RUNNING, "replicating"))
            }
        }
        followerGate = FollowerGate(firstProjection, watch)
    }

    /**
     * The body is [NonCancellable] because it is reached from inside the [followerGate] watch on the
     * backstop path and cancels that very job. Without it the release would abort at the first
     * suspension point and leave the store gated, which is the failure it exists to prevent.
     */
    private suspend fun endExternalControl(): Unit = withContext(NonCancellable) {
        followerGate?.watch?.cancel()
        followerGate = null
        externalState()?.endControl()
    }

    /**
     * Hands a gated store back to local control and reboots it as an ordinary client.
     *
     * Only used when the client is no longer a follower at all, meaning the server assigned it
     * some other role. A missing publisher or a publisher that has not sent state yet is not a
     * reason to release: configuring [DevToolsConfig.defaultRole] as LISTENER declares the
     * intent to follow, so the client waits for a publisher rather than deciding for the
     * developer that it should stop. A client that wants to boot normally and choose later
     * should start UNASSIGNED and call [follow] when it is ready.
     *
     * The role is marked abandoned so that the restart, which starts this service again, does
     * not immediately re-request LISTENER and bounce between roles. An explicit [follow]
     * clears that.
     */
    private suspend fun releaseExternalControl(reason: String) {
        val context = context ?: return
        if (externalState()?.isUnderControl != true) return
        withContext(NonCancellable) {
            ReaktivDebug.warn("DevTools: Resuming local control ($reason)")
            listenerRoleAbandoned = true
            report(
                ServiceStatus(ServiceState.DEGRADED, "resumed local control: $reason")
            )
            endExternalControl()
            context.storeAccessor.resetAsync()
        }
    }

    public suspend fun send(message: DevToolsMessage): Boolean =
        connection?.send(message) ?: false

    private suspend fun requestRole(role: ClientRole, publisherClientId: String?) {
        val sent = send(
            DevToolsMessage.RoleAssignment(
                targetClientId = clientId,
                role = role,
                publisherClientId = publisherClientId
            )
        )
        if (!sent) ReaktivDebug.warn("DevTools: Could not request the $role role, not connected")
    }

    private suspend fun handleServerMessage(message: DevToolsMessage) {
        if (message is DevToolsMessage.Targeted && message.targetClientId != clientId) return
        when (message) {
            is DevToolsMessage.RegistrationRefused -> refused(message.reason)
            is DevToolsMessage.RoleAssignment -> handleRoleAssignment(message)
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
            is DevToolsMessage.FetchNetworkBody -> {
                val slice = NetworkTap.bodySlice(
                    requestId = message.requestId,
                    part = message.part,
                    offset = message.offset,
                    maxBytes = message.maxBytes
                )
                send(
                    DevToolsMessage.NetworkBodyChunk(
                        clientId = clientId,
                        requestId = message.requestId,
                        part = message.part,
                        content = slice?.content,
                        offset = slice?.offset ?: message.offset,
                        nextOffset = slice?.nextOffset ?: message.offset,
                        totalBytes = slice?.totalBytes ?: 0,
                        isLast = slice?.isLast ?: true
                    )
                )
            }
            is DevToolsMessage.AddMarkerRequest -> {
                context?.capture?.addMarker(
                    label = message.label,
                    note = message.note,
                    source = "remote",
                    timestampMs = message.timestampMs,
                    afterActionIndex = message.afterActionIndex
                )
            }
            is DevToolsMessage.ServiceRequest -> serviceLaunch { send(answer(message)) }
            else -> {}
        }
    }

    private suspend fun refused(reason: String) {
        manuallyDisconnected = true
        reconnectJob?.cancel()
        ReaktivDebug.warn("DevTools: The server refused this client - $reason")
        report(ServiceStatus(ServiceState.DEGRADED, reason))
        releaseExternalControl("the server refused this client")
    }

    private suspend fun handleRoleAssignment(assignment: DevToolsMessage.RoleAssignment) {
        if (assignment.role == ClientRole.LISTENER && externalState() == null) {
            currentRole = ClientRole.UNASSIGNED
            refuseToFollow()
            requestRole(ClientRole.UNASSIGNED, null)
            return
        }
        val previousRole = currentRole
        currentRole = assignment.role
        if (assignment.role == ClientRole.LISTENER) {
            beginExternalControl()
        } else {
            releaseExternalControl("assigned role ${assignment.role}")
        }
        send(
            DevToolsMessage.RoleAcknowledgment(
                clientId = clientId,
                role = assignment.role,
                message = "Role changed to ${assignment.role}"
            )
        )
        // One status rather than two competing writes. A listener granted the role without a
        // publisher is waiting, not running, and reporting it as running hid the fact that
        // nothing was going to arrive yet.
        val status = when {
            assignment.role == ClientRole.PUBLISHER -> ServiceStatus(ServiceState.RUNNING, "publishing")
            assignment.role == ClientRole.LISTENER && assignment.publisherClientId == null ->
                ServiceStatus(ServiceState.STARTING, "waiting for a publisher")
            assignment.role == ClientRole.LISTENER ->
                ServiceStatus(ServiceState.RUNNING, "following ${assignment.publisherClientId}")
            listenerRoleAbandoned && externalState() == null -> ServiceStatus(ServiceState.DEGRADED, FOLLOW_DENIED)
            else -> ServiceStatus(ServiceState.RUNNING, "connected")
        }
        report(status)
        if (assignment.role == ClientRole.PUBLISHER && previousRole != ClientRole.PUBLISHER) {
            sendSessionHistorySync()
        }
    }

    private suspend fun answer(request: DevToolsMessage.ServiceRequest): DevToolsMessage.ServiceReply {
        fun reply(result: JsonElement? = null, error: String? = null) =
            DevToolsMessage.ServiceReply(clientId, request.requestId, request.service, result, error)

        if (!config.allowRemoteRequests) return reply(error = "Remote requests are turned off on this device")
        if (currentRole != ClientRole.PUBLISHER) return reply(error = "This device is not publishing")
        val target = context?.storeAccessor?.selectLogic<ToolingLogic>()?.service(request.service)
            ?: return reply(error = "No tooling service named '${request.service}' on this device")
        return try {
            target.onRequest(request.request, request.payload)?.let { reply(result = it) }
                ?: reply(error = "'${request.service}' does not handle '${request.request}'")
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            reply(error = failure.message ?: failure::class.simpleName ?: "The request failed")
        }
    }

    private suspend fun sendSessionHistorySync() {
        val capture = context?.capture ?: return
        if (!isConnected()) return
        val chunks = try {
            capture.getSessionHistory().withBaseline().chunked()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (e: Exception) {
            ReaktivDebug.warn("DevTools: Could not read the session history - ${e.message}")
            return
        }
        chunks.forEachIndexed { index, chunk ->
            send(DevToolsMessage.SessionHistoryChunk(clientId, index, chunks.size, chunk))
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
        if (json == null) return this
        val states = context?.storeAccessor?.getAllStates() ?: return this
        return copy(initialStateJson = encodeTreePerModule(states))
    }

    private suspend fun sendFullStateSync() {
        val context = context ?: return
        if (json == null) return
        if (!isConnected()) return
        send(
            DevToolsMessage.StateSync(
                fromClientId = clientId,
                timestamp = currentTimeMillis(),
                stateJson = encodeTreePerModule(context.storeAccessor.getAllStates())
            )
        )
    }

    /**
     * Encodes the state tree one module at a time so one unserializable module cannot suppress
     * the whole baseline.
     *
     * Encoding the map in a single call means any module that cannot be serialized, typically a
     * sealed hierarchy whose subclasses were never registered through CustomTypeRegistrar,
     * throws and leaves the observer with no state at all rather than merely missing that one
     * module. The follower then reports "publisher sent no state", which is accurate but points
     * at the wrong end of the wire.
     *
     * Modules that fail are named in the service status so the missing registration is visible
     * on the publisher, which is the only side that can fix it.
     */
    private suspend fun encodeTreePerModule(states: Map<String, ModuleState>): String {
        val context = context ?: return "{}"
        val tree = context.capture.encodeStateTree(states)
        val failed = tree.failed
        if (failed.isNotEmpty()) {
            failed.forEach { (module, reason) ->
                ReaktivDebug.warn("DevTools: Cannot publish $module - $reason")
            }
            report(
                ServiceStatus(
                    ServiceState.DEGRADED,
                    "cannot publish ${failed.keys.joinToString()}: ${failed.values.first()}"
                )
            )
        }
        return tree.modules.toString()
    }

    private companion object {
        const val CONTROL_BY_COMMAND: String =
            "Control DevTools by dispatching DevToolsCommands, which reaches the service through the tooling module."
        const val FOLLOW_DENIED: String =
            "cannot follow, this store does not accept outside state. Remove externalState(ExternalStatePolicy.Deny) " +
                "from createStore to allow it"
        const val DELTA_CONFLATION_WINDOW_MS: Long = 75L
        const val RECONNECT_INITIAL_DELAY_MS: Long = 1000L
        const val RECONNECT_MAX_DELAY_MS: Long = 30_000L
        const val FIRST_PROJECTION_SLOW_MS: Long = 10_000L
        const val BATCH_WINDOW_MS: Long = 300L
        const val LOG_BUFFER: Int = 512
        const val LOG_BATCH_LIMIT: Int = 100
        const val NETWORK_BUFFER: Int = 256
        const val NETWORK_BATCH_LIMIT: Int = 50
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
                    report(
                        ServiceStatus(
                            ServiceState.DEGRADED,
                            "desynced: field delta for ${event.moduleName} before first sync"
                        )
                    )
                    return
                }
                mergeFields(base, incoming)
            } else {
                incoming
            }
            followerShadow[event.moduleName] = merged
            val state: ModuleState = json.decodeFromString(
                PolymorphicSerializer(ModuleState::class),
                restoreRedactedModuleElement(json, merged).toString()
            )
            externalState()?.hydrate(mapOf(event.moduleName to state), HydrateSource.Replication)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (e: Exception) {
            ReaktivDebug.warn("DevTools: Failed to apply action delta - ${e.message}")
            report(
                ServiceStatus(
                    ServiceState.DEGRADED,
                    "delta rejected for ${event.moduleName}: ${e.message}"
                )
            )
        }
    }

    private class DecodedTree(
        val applied: Map<String, ModuleState>,
        val failed: Map<String, String>
    )

    /**
     * Decodes a full state tree one module at a time so one undecodable module cannot blank
     * out the whole projection.
     *
     * Decoding the tree in a single call means any module the follower cannot reconstruct
     * aborts every other module with it. That is not hypothetical: NavigationEntry serialises
     * as a route path and rehydrates by resolving that path against the follower's own graph,
     * so a single route the follower does not declare throws and, decoded as one map, would
     * leave the follower with no replicated state at all rather than merely no navigation.
     *
     * Failures are returned per module so the reason, which names the offending path, can be
     * surfaced instead of swallowed.
     */
    private fun decodeTreePerModule(json: Json, stateJson: String): DecodedTree {
        val applied = mutableMapOf<String, ModuleState>()
        val failed = mutableMapOf<String, String>()
        val tree = json.parseToJsonElement(stateJson).jsonObject
        tree.forEach { (moduleName, element) ->
            (element as? JsonObject)?.let { followerShadow[moduleName] = it }
            try {
                val safe = (element as? JsonObject)
                    ?.let { restoreRedactedModuleElement(json, it) }
                    ?: element
                applied[moduleName] = json.decodeFromString(
                    PolymorphicSerializer(ModuleState::class), safe.toString()
                )
            } catch (e: Exception) {
                failed[moduleName] = e.message ?: "decode failed"
            }
        }
        return DecodedTree(applied, failed)
    }

    private suspend fun applyStateSync(sync: DevToolsMessage.StateSync) {
        val access = externalState() ?: return
        val json = json ?: return
        try {
            val decoded = decodeTreePerModule(json, sync.stateJson)
            if (decoded.applied.isNotEmpty()) {
                access.hydrate(decoded.applied, HydrateSource.Replication)
                onFirstProjectionApplied(decoded.applied.keys)
            }
            if (decoded.failed.isNotEmpty()) {
                decoded.failed.forEach { (module, reason) ->
                    ReaktivDebug.warn("DevTools: Cannot replicate $module - $reason")
                }
                report(
                    ServiceStatus(
                        ServiceState.DEGRADED,
                        "cannot replicate ${decoded.failed.keys.joinToString()}: " +
                            decoded.failed.values.first()
                    )
                )
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (e: Exception) {
            ReaktivDebug.warn("DevTools: Failed to apply state sync - ${e.message}")
            report(
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
        val gate = followerGate?.firstProjection ?: return
        if (gate.isCompleted) return
        gate.complete(Unit)
        report(
            ServiceStatus(ServiceState.RUNNING, "replicating ${appliedModules.size} modules")
        )
    }
}
