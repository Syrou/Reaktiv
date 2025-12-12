package io.github.syrou.reaktiv.devtools.middleware

import io.github.syrou.reaktiv.core.ExperimentalReaktivApi
import io.github.syrou.reaktiv.core.Middleware
import io.github.syrou.reaktiv.core.ModuleAction
import io.github.syrou.reaktiv.core.ModuleState
import io.github.syrou.reaktiv.core.Store
import io.github.syrou.reaktiv.core.StoreAccessor
import io.github.syrou.reaktiv.devtools.client.DevToolsConnection
import io.github.syrou.reaktiv.devtools.protocol.ClientRole
import io.github.syrou.reaktiv.devtools.protocol.DevToolsMessage
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
 * Automatically uses the Store's serializers, which include:
 * - All module states
 * - Navigation-related types (from NavigationModule)
 * - Custom types registered via CustomTypeRegistrar
 *
 * Example usage:
 * ```kotlin
 * val store = createStore {
 *     module(CounterModule)
 *     module(navigationModule)
 *
 *     middlewares(
 *         DevToolsMiddleware(
 *             config = DevToolsConfig(
 *                 serverUrl = "ws://192.168.1.100:8080/ws",
 *                 clientName = "My Phone",
 *                 platform = "Samsung Galaxy S23"
 *             ),
 *             scope = lifecycleScope
 *         ).middleware
 *     )
 * }
 * ```
 *
 * If your module state contains custom types, implement CustomTypeRegistrar:
 * ```kotlin
 * object UserModule : Module<UserState, UserAction>, CustomTypeRegistrar {
 *     override fun registerAdditionalSerializers(builder: SerializersModuleBuilder) {
 *         builder.polymorphic(AppSettings::class) {
 *             subclass(BasicSettings::class)
 *             subclass(AdvancedSettings::class)
 *         }
 *     }
 * }
 * ```
 *
 * The serializers will be automatically picked up by both the Store and DevToolsMiddleware.
 *
 * @param config DevTools configuration
 * @param scope CoroutineScope for async operations
 * @param stateSerializers Optional SerializersModule. If not provided, will automatically use Store's serializers
 */
@OptIn(ExperimentalReaktivApi::class)
class DevToolsMiddleware(
    private val config: DevToolsConfig,
    private val scope: CoroutineScope,
    private val stateSerializers: SerializersModule? = null
) {
    private lateinit var json: Json

    private val connection = DevToolsConnection(config.serverUrl)

    private val _currentRole = MutableStateFlow(ClientRole.UNASSIGNED)
    val currentRole: StateFlow<ClientRole> = _currentRole.asStateFlow()

    private var storeAccessorRef: StoreAccessor? = null

    init {
        if (config.enabled) {
            scope.launch {
                connection.connect(config.clientId, config.clientName, config.platform)

                connection.observeMessages { message ->
                    handleServerMessage(message)
                }
            }
        }
    }

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

        val role = _currentRole.value

        when (role) {
            ClientRole.PUBLISHER -> {
                val newState = updatedState(action)

                if (config.allowActionCapture && config.allowStateCapture) {
                    scope.launch {
                        val allStates = getAllStates()
                        sendActionWithState(action, allStates)
                    }
                }
            }

            ClientRole.LISTENER, ClientRole.OBSERVER, ClientRole.UNASSIGNED -> {
                updatedState(action)
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
                if (_currentRole.value == ClientRole.LISTENER && config.allowStateSync) {
                    applyStateSync(message)
                }
            }

            else -> {
                // Ignore other message types (handled by UI)
            }
        }
    }

    private suspend fun handleRoleAssignment(assignment: DevToolsMessage.RoleAssignment) {
        _currentRole.value = assignment.role

        connection.send(
            DevToolsMessage.RoleAcknowledgment(
                clientId = config.clientId,
                role = assignment.role,
                success = true,
                message = "Role changed to ${assignment.role}"
            )
        )

        println("DevTools: Role changed to ${assignment.role}")
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
        try {
            val stateJson = json.encodeToString(states)

            val message = DevToolsMessage.ActionDispatched(
                clientId = config.clientId,
                timestamp = getCurrentTimestamp(),
                actionType = action::class.simpleName ?: "Unknown",
                actionData = action.toString(),
                resultingStateJson = stateJson
            )
            connection.send(message)
        } catch (e: Exception) {
            println("DevTools: Failed to send action with state - ${e.message}")
        }
    }

    /**
     * Disconnects from the DevTools server.
     */
    suspend fun disconnect() {
        connection.disconnect()
    }
}

private fun getCurrentTimestamp(): Long {
    return Clock.System.now().toEpochMilliseconds()
}
