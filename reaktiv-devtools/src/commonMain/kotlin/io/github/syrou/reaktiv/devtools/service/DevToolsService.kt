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
import io.github.syrou.reaktiv.introspection.tooling.ToolingAction
import io.github.syrou.reaktiv.introspection.tooling.ToolingCommand
import io.github.syrou.reaktiv.introspection.tooling.ToolingService
import io.github.syrou.reaktiv.introspection.capture.chunked
import io.github.syrou.reaktiv.introspection.protocol.CapturedAction
import io.github.syrou.reaktiv.introspection.protocol.DeltaKind
import io.github.syrou.reaktiv.introspection.protocol.mergeCapturedDeltas
import io.github.syrou.reaktiv.introspection.protocol.mergeFieldJson
import io.github.syrou.reaktiv.introspection.tooling.ToolingServiceContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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

    override fun createMiddleware(): Middleware = { action, getAllStates, _, updatedState ->
        if (getAllStatesRef == null) {
            getAllStatesRef = getAllStates
        }
        if (currentRole != ClientRole.LISTENER || action is ToolingAction) {
            updatedState(action)
        }
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

        val observer = DevToolsLogicObserver(
            clientId = clientId,
            scope = context.storeAccessor,
            isConnected = { isConnected() },
            sendMessage = { send(it) }
        )
        logicObserver = observer
        LogicTracer.addObserver(observer)

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

        if (config.enabled && config.autoConnect && config.serverUrl != null) {
            connect(config.serverUrl, config.defaultRole)
        } else {
            context.setStatus(ServiceStatus(ServiceState.STOPPED, "awaiting connect"))
        }
    }

    override suspend fun stop() {
        disconnect()
        logicObserver?.let { LogicTracer.removeObserver(it) }
        logicObserver = null
    }

    public suspend fun connect(serverUrl: String, role: ClientRole? = config.defaultRole) {
        val context = context ?: return
        connection?.disconnect()
        currentServerUrl = serverUrl
        context.setStatus(ServiceStatus(ServiceState.STARTING, "connecting to $serverUrl"))
        val newConnection = DevToolsConnection(serverUrl)
        connection = newConnection
        try {
            newConnection.connect(clientId, context.config.clientName, context.config.platform)
            newConnection.observeMessages { message -> handleServerMessage(message) }
            context.setStatus(ServiceStatus(ServiceState.RUNNING, "connected to $serverUrl"))
            if (role != null) {
                requestRole(role, null)
            }
        } catch (e: Exception) {
            context.setStatus(ServiceStatus(ServiceState.DEGRADED, e.message))
            ReaktivDebug.warn("DevTools: Failed to connect - ${e.message}")
        }
    }

    public suspend fun disconnect() {
        connection?.disconnect()
        connection = null
        currentRole = ClientRole.UNASSIGNED
        context?.setStatus(ServiceStatus(ServiceState.STOPPED, "disconnected"))
    }

    public suspend fun reconnect() {
        currentServerUrl?.let { connect(it) }
    }

    public suspend fun follow(publisherClientId: String? = null) {
        requestRole(ClientRole.LISTENER, publisherClientId)
    }

    public suspend fun unfollow() {
        requestRole(ClientRole.UNASSIGNED, null)
        val wasFollowing = currentRole == ClientRole.LISTENER
        currentRole = ClientRole.UNASSIGNED
        context?.setStatus(ServiceStatus(ServiceState.RUNNING, "connected"))
        if (wasFollowing) {
            (context?.storeAccessor as? Store)?.resetAsync()
        }
    }

    public fun isConnected(): Boolean =
        connection?.connectionState?.value == ConnectionState.CONNECTED

    public suspend fun send(message: DevToolsMessage) {
        connection?.send(message)
    }

    private val pendingDeltas = mutableMapOf<String, CapturedAction>()
    private var deltaFlushJob: Job? = null

    private fun conflatedSend(scope: CoroutineScope, event: CapturedAction) {
        val pending = pendingDeltas[event.moduleName]
        pendingDeltas[event.moduleName] = if (pending != null) mergeCapturedDeltas(pending, event) else event
        if (deltaFlushJob?.isActive != true) {
            deltaFlushJob = scope.launch {
                delay(DELTA_CONFLATION_WINDOW_MS)
                val batch = pendingDeltas.values.toList()
                pendingDeltas.clear()
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
                    sendFullStateSync()
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
            val history = capture.getSessionHistory()
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
    }

    private val followerShadow = mutableMapOf<String, JsonObject>()

    private suspend fun applyActionDelta(event: CapturedAction) {
        val json = json ?: return
        try {
            val incoming = json.parseToJsonElement(event.stateDeltaJson).jsonObject
            val merged = if (event.deltaKind == DeltaKind.FIELDS) {
                val base = followerShadow[event.moduleName] ?: return
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
        } catch (e: Exception) {
            ReaktivDebug.warn("DevTools: Failed to apply state sync - ${e.message}")
        }
    }
}
