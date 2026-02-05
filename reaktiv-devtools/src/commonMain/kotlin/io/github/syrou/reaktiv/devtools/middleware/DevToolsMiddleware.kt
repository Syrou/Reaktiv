package io.github.syrou.reaktiv.devtools.middleware

import io.github.syrou.reaktiv.core.ExperimentalReaktivApi
import io.github.syrou.reaktiv.core.Middleware
import io.github.syrou.reaktiv.core.ModuleAction
import io.github.syrou.reaktiv.core.ModuleState
import io.github.syrou.reaktiv.core.Store
import io.github.syrou.reaktiv.core.StoreAccessor
import io.github.syrou.reaktiv.core.tracing.LogicTracer
import io.github.syrou.reaktiv.core.util.selectLogic
import io.github.syrou.reaktiv.introspection.IntrospectionAction
import io.github.syrou.reaktiv.introspection.capture.SessionCapture
import io.github.syrou.reaktiv.devtools.DevToolsAction
import io.github.syrou.reaktiv.devtools.DevToolsLogic
import io.github.syrou.reaktiv.devtools.protocol.ClientRole
import io.github.syrou.reaktiv.devtools.protocol.DevToolsMessage
import io.github.syrou.reaktiv.devtools.tracing.DevToolsLogicObserver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlin.time.Clock

/**
 * Middleware for connecting Reaktiv store to DevTools server.
 *
 * This class is typically instantiated internally by DevToolsModule.
 * Use DevToolsModule directly for the simplest setup:
 *
 * ```kotlin
 * val store = createStore {
 *     module(DevToolsModule(
 *         config = DevToolsConfig(
 *             serverUrl = "ws://192.168.1.100:8080/ws",
 *             platform = "Android"
 *         ),
 *         scope = lifecycleScope
 *     ))
 *     // ... other modules
 * }
 * ```
 *
 * The middleware intercepts all actions and:
 * - Handles DevToolsAction (Connect, Disconnect, Reconnect)
 * - Broadcasts other actions to the DevTools server when connected
 *
 * @param config DevTools configuration
 * @param scope CoroutineScope for async operations
 * @param stateSerializers Optional SerializersModule. If not provided, will use Store's serializers
 */
@OptIn(ExperimentalReaktivApi::class)
class DevToolsMiddleware(
    private val config: DevToolsConfig,
    private val scope: CoroutineScope,
    private val stateSerializers: SerializersModule? = null
) {
    private lateinit var json: Json

    private val _currentRole = MutableStateFlow(ClientRole.UNASSIGNED)
    val currentRole: StateFlow<ClientRole> = _currentRole.asStateFlow()

    private var storeAccessorRef: StoreAccessor? = null
    private var devToolsLogic: DevToolsLogic? = null
    private var logicObserver: DevToolsLogicObserver? = null
    private var initialized = false

    private val sessionCapture: SessionCapture?
        get() = devToolsLogic?.getSessionCapture()

    /**
     * The middleware function to be used with Reaktiv store.
     */
    val middleware: Middleware = middleware@{ action, getAllStates, storeAccessor, updatedState ->
        if (storeAccessorRef == null) {
            storeAccessorRef = storeAccessor
            if (!::json.isInitialized) {
                val serializers = stateSerializers ?: (storeAccessor as? Store)?.serializersModule
                    ?: throw IllegalStateException("DevToolsMiddleware requires either stateSerializers parameter or Store with serializersModule")
                json = Json {
                    serializersModule = serializers
                    ignoreUnknownKeys = true
                }
            }
        }

        if (!config.enabled) {
            updatedState(action)
            return@middleware
        }

        // Initialize logic and auto-connect if needed (only once)
        if (!initialized) {
            initialized = true
            scope.launch {
                initializeLogic(storeAccessor)
            }
        }

        if (action is DevToolsAction || action is IntrospectionAction) {
            updatedState(action)
            if (action is DevToolsAction) {
                scope.launch {
                    handleDevToolsAction(action)
                }
            }
            return@middleware
        }

        val role = _currentRole.value

        when (role) {
            ClientRole.PUBLISHER -> {
                updatedState(action)

                if (config.allowActionCapture && config.allowStateCapture) {
                    scope.launch {
                        val allStates = getAllStates()
                        sendActionWithState(action, allStates)
                    }
                }
            }

            ClientRole.LISTENER -> {
                return@middleware
            }

            ClientRole.ORCHESTRATOR, ClientRole.UNASSIGNED -> {
                updatedState(action)
            }
        }
    }

    private suspend fun initializeLogic(storeAccessor: StoreAccessor) {
        try {
            devToolsLogic = storeAccessor.selectLogic<DevToolsLogic>()

            devToolsLogic?.observeMessages { message ->
                handleServerMessage(message)
            }

            // Register logic tracing observer (sessionCapture is now owned by Logic)
            devToolsLogic?.let { logic ->
                val capture = logic.getSessionCapture()
                logicObserver = DevToolsLogicObserver(config, logic, scope, capture)
                LogicTracer.addObserver(logicObserver!!)
                println("DevTools: Logic tracing observer registered")
            }

            // Auto-connect if serverUrl is configured
            if (config.serverUrl != null) {
                devToolsLogic?.connect(config.serverUrl)

                // Request default role after connection
                if (config.defaultRole != DefaultDeviceRole.NONE) {
                    requestDefaultRole()
                }
            }
        } catch (e: Exception) {
            println("DevTools: Failed to initialize logic - ${e.message}")
            println("DevTools: Make sure DevToolsModule.create(config) is registered in your store")
        }
    }

    private suspend fun requestDefaultRole() {
        val logic = devToolsLogic ?: return
        if (!logic.isConnected()) {
            println("DevTools: Cannot request role - not connected")
            return
        }

        val role = when (config.defaultRole) {
            DefaultDeviceRole.PUBLISHER -> ClientRole.PUBLISHER
            DefaultDeviceRole.LISTENER -> ClientRole.LISTENER
            DefaultDeviceRole.NONE -> return
        }

        try {
            val message = DevToolsMessage.RoleAssignment(
                targetClientId = config.clientId,
                role = role,
                publisherClientId = null
            )
            logic.send(message)
            println("DevTools: Requested default role: $role")
        } catch (e: Exception) {
            println("DevTools: Failed to request default role - ${e.message}")
        }
    }

    private suspend fun handleDevToolsAction(action: DevToolsAction) {
        val logic = devToolsLogic ?: return

        when (action) {
            is DevToolsAction.Connect -> {
                logic.connect(action.serverUrl, action.clientName)
            }
            is DevToolsAction.Disconnect -> {
                logic.disconnect()
            }
            is DevToolsAction.Reconnect -> {
                logic.reconnect()
            }
            is DevToolsAction.UpdateConnectionState -> {
                // Already handled by reducer, nothing to do here
            }
        }
    }

    private suspend fun handleServerMessage(message: DevToolsMessage) {
        when (message) {
            is DevToolsMessage.RoleAssignment -> {
                if (message.targetClientId == config.clientId) {
                    handleRoleAssignment(message)
                }
            }

            is DevToolsMessage.StateSync -> {
                if (_currentRole.value == ClientRole.LISTENER) {
                    applyStateSync(message)
                }
            }

            else -> {
                // Ignore other message types (handled by UI)
            }
        }
    }

    private suspend fun handleRoleAssignment(assignment: DevToolsMessage.RoleAssignment) {
        val previousRole = _currentRole.value
        _currentRole.value = assignment.role

        devToolsLogic?.send(
            DevToolsMessage.RoleAcknowledgment(
                clientId = config.clientId,
                role = assignment.role,
                success = true,
                message = "Role changed to ${assignment.role}"
            )
        )

        println("DevTools: Role changed to ${assignment.role}")

        if (assignment.role == ClientRole.PUBLISHER && previousRole != ClientRole.PUBLISHER) {
            sendSessionHistorySync()
        }
    }

    private suspend fun sendSessionHistorySync() {
        val capture = sessionCapture ?: return
        val logic = devToolsLogic ?: return
        if (!logic.isConnected()) return

        val history = capture.getSessionHistory()

        try {
            val message = DevToolsMessage.SessionHistorySync(
                clientId = config.clientId,
                sessionStartTime = history.startTime,
                actionEvents = history.actions.map { DevToolsMessage.ActionDispatched.fromCaptured(it) },
                logicStartedEvents = history.logicStarted.map { DevToolsMessage.LogicMethodStarted.fromCaptured(it) },
                logicCompletedEvents = history.logicCompleted.map { DevToolsMessage.LogicMethodCompleted.fromCaptured(it) },
                logicFailedEvents = history.logicFailed.map { DevToolsMessage.LogicMethodFailed.fromCaptured(it) }
            )
            logic.send(message)
            println("DevTools: Sent session history sync (${history.actions.size} actions)")
        } catch (e: Exception) {
            println("DevTools: Failed to send session history sync - ${e.message}")
        }
    }

    private suspend fun applyStateSync(sync: DevToolsMessage.StateSync) {
        val storeAccessor = storeAccessorRef ?: return

        try {
            val states: Map<String, ModuleState> = json.decodeFromString(sync.stateJson)

            val internalOps = storeAccessor.asInternalOperations()
            internalOps?.applyExternalStates(states)

            println("DevTools: Applied state from ${sync.fromClientId}")
        } catch (e: Exception) {
            println("DevTools: Failed to apply state sync - ${e.message}")
        }
    }

    private suspend fun sendActionWithState(action: ModuleAction, states: Map<String, ModuleState>) {
        val logic = devToolsLogic ?: return
        if (!logic.isConnected()) return

        try {
            val stateJson = json.encodeToString(states)

            val message = DevToolsMessage.ActionDispatched(
                clientId = config.clientId,
                timestamp = getCurrentTimestamp(),
                actionType = action::class.simpleName ?: "Unknown",
                actionData = action.toString(),
                resultingStateJson = stateJson
            )

            sessionCapture?.captureAction(message.toCaptured())

            logic.send(message)
        } catch (e: Exception) {
            println("DevTools: Failed to send action with state - ${e.message}")
        }
    }

    /**
     * Disconnects from the DevTools server.
     */
    suspend fun disconnect() {
        devToolsLogic?.disconnect()
    }

    /**
     * Cleans up resources including the logic tracing observer.
     * Note: SessionCapture cleanup should be done via DevToolsLogic.cleanup()
     */
    fun cleanup() {
        logicObserver?.let { observer ->
            LogicTracer.removeObserver(observer)
            println("DevTools: Logic tracing observer removed")
        }
        logicObserver = null
    }
}

private fun getCurrentTimestamp(): Long {
    return Clock.System.now().toEpochMilliseconds()
}
