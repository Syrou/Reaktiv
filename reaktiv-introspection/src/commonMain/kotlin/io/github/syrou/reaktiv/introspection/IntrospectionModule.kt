package io.github.syrou.reaktiv.introspection

import io.github.syrou.reaktiv.core.Middleware
import io.github.syrou.reaktiv.core.ModuleAction
import io.github.syrou.reaktiv.core.ModuleLogic
import io.github.syrou.reaktiv.core.ModuleState
import io.github.syrou.reaktiv.core.ModuleWithLogic
import io.github.syrou.reaktiv.core.Store
import io.github.syrou.reaktiv.core.StoreAccessor
import io.github.syrou.reaktiv.core.tracing.LogicTracer
import io.github.syrou.reaktiv.introspection.capture.SessionCapture
import io.github.syrou.reaktiv.introspection.protocol.CapturedAction
import io.github.syrou.reaktiv.introspection.tracing.IntrospectionLogicObserver
import kotlinx.serialization.PolymorphicSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.time.Clock

/**
 * State for introspection module.
 * Minimal state - just tracks if capture is active.
 */
@Serializable
data class IntrospectionState(
    val isCapturing: Boolean = false
) : ModuleState

/**
 * Actions for introspection module.
 */
@Serializable
sealed class IntrospectionAction : ModuleAction(IntrospectionModule::class) {
    @Serializable
    internal data object StartCapture : IntrospectionAction()

    @Serializable
    internal data object StopCapture : IntrospectionAction()
}

/**
 * Logic class for introspection and session capture operations.
 *
 * The SessionCapture is created externally and passed to both IntrospectionModule
 * and DevToolsModule, ensuring a single point of capture:
 * ```kotlin
 * val sessionCapture = SessionCapture()
 * val introspectionModule = IntrospectionModule(config, sessionCapture, platformContext)
 * val devToolsModule = DevToolsModule(devToolsConfig, scope, sessionCapture)
 *
 * // Install crash handler (any platform)
 * CrashHandler(platformContext, sessionCapture).install()
 *
 * // Export session to Downloads
 * val path = introspectionLogic.exportSessionToDownloads()
 * ```
 */
class IntrospectionLogic internal constructor(
    private val storeAccessor: StoreAccessor,
    private val config: IntrospectionConfig,
    private val sessionCapture: SessionCapture,
    platformContext: PlatformContext
) : ModuleLogic<IntrospectionAction>() {

    private var logicObserver: IntrospectionLogicObserver? = null
    private val sessionFileExport = SessionFileExport(platformContext)

    init {
        if (config.enabled) {
            if (!sessionCapture.isStarted()) {
                sessionCapture.start(config.clientId, config.clientName, config.platform)
            }
            logicObserver = IntrospectionLogicObserver(config.clientId, sessionCapture)
            LogicTracer.addObserver(logicObserver!!)
        }
    }

    override suspend fun beforeReset() {
        sessionCapture.clear()
        logicObserver?.let { LogicTracer.removeObserver(it) }
        logicObserver = null
    }

    /**
     * Gets the session capture instance for crash handling and session export.
     *
     * @return SessionCapture instance
     */

    fun getSessionCapture(): SessionCapture = sessionCapture

    /**
     * Exports the current session as a JSON string.
     *
     * @return JSON string that can be imported as a ghost device in DevTools
     */
    fun exportSessionJson(): String = sessionCapture.exportSession()

    /**
     * Exports the current session with crash information.
     *
     * @param throwable The exception that caused the crash
     * @return JSON string with crash info
     */
    fun exportCrashSessionJson(throwable: Throwable): String =
        sessionCapture.exportCrashSession(throwable)

    /**
     * Exports the current session to the device's Downloads folder.
     *
     * @param fileName Optional custom file name (defaults to timestamped name)
     * @return Path where the file was saved
     */
    fun exportSessionToDownloads(fileName: String? = null): String {
        val json = sessionCapture.exportSession()
        val actualFileName = fileName
            ?: "reaktiv_session_${Clock.System.now().toEpochMilliseconds()}.json"
        return sessionFileExport.saveToDownloads(json, actualFileName)
    }

    /**
     * Exports the current session with crash information to the device's Downloads folder.
     *
     * @param throwable The exception that caused the crash
     * @param fileName Optional custom file name (defaults to timestamped name)
     * @return Path where the file was saved
     */
    fun exportCrashSessionToDownloads(throwable: Throwable, fileName: String? = null): String {
        val json = sessionCapture.exportCrashSession(throwable)
        val actualFileName = fileName
            ?: "reaktiv_crash_${Clock.System.now().toEpochMilliseconds()}.json"
        return sessionFileExport.saveToDownloads(json, actualFileName)
    }

    /**
     * Cleans up resources.
     */
    fun cleanup() {
        logicObserver?.let { observer ->
            LogicTracer.removeObserver(observer)
        }
        logicObserver = null
        sessionCapture.stop()
    }
}

/**
 * Lightweight module for session capture and introspection.
 *
 * Use this module standalone in production apps to capture session data
 * for crash reports without including DevTools networking code.
 *
 * The SessionCapture instance is created externally and shared across modules:
 * ```kotlin
 * val sessionCapture = SessionCapture()
 * val introspectionConfig = IntrospectionConfig(
 *     clientName = "MyApp",
 *     platform = "Android ${Build.VERSION.RELEASE}"
 * )
 *
 * val store = createStore {
 *     module(IntrospectionModule(introspectionConfig, sessionCapture, platformContext))
 *     // ... other modules
 * }
 *
 * // Install crash handler (any platform)
 * CrashHandler(platformContext, sessionCapture).install()
 * ```
 *
 * The captured session JSON is compatible with DevTools ghost device import.
 *
 * @param config Introspection configuration for identity and behavior
 * @param sessionCapture Shared SessionCapture instance for recording events
 * @param platformContext Platform-specific context for file operations
 */
class IntrospectionModule(
    private val config: IntrospectionConfig,
    private val sessionCapture: SessionCapture,
    private val platformContext: PlatformContext
) : ModuleWithLogic<IntrospectionState, IntrospectionAction, IntrospectionLogic> {

    override val initialState = IntrospectionState(isCapturing = config.enabled)

    override val reducer: (IntrospectionState, IntrospectionAction) -> IntrospectionState = { state, action ->
        when (action) {
            is IntrospectionAction.StartCapture -> state.copy(isCapturing = true)
            is IntrospectionAction.StopCapture -> state.copy(isCapturing = false)
        }
    }

    override val createLogic: (StoreAccessor) -> IntrospectionLogic = { storeAccessor ->
        IntrospectionLogic(storeAccessor, config, sessionCapture, platformContext)
    }

    override val createMiddleware: (() -> Middleware) = {
        IntrospectionMiddleware(config, sessionCapture).middleware
    }
}

/**
 * Middleware that captures all dispatched actions.
 */
internal class IntrospectionMiddleware(
    private val config: IntrospectionConfig,
    private val sessionCapture: SessionCapture
) {
    private lateinit var json: Json
    private var initialized = false
    private var initialStateCaptured = false

    val middleware: Middleware = middleware@{ action, getAllStates, storeAccessor, updatedState ->
        if (!config.enabled) {
            updatedState(action)
            return@middleware
        }

        if (!initialized) {
            initialized = true
            val serializers = (storeAccessor as? Store)?.serializersModule
            json = Json {
                if (serializers != null) {
                    serializersModule = serializers
                }
                ignoreUnknownKeys = true
            }
        }

        if (!initialStateCaptured && sessionCapture.isStarted() && action !is IntrospectionAction) {
            initialStateCaptured = true
            try {
                val allStates = getAllStates()
                val mapSerializer = MapSerializer(
                    String.serializer(),
                    PolymorphicSerializer(ModuleState::class)
                )
                val initialStateJson = json.encodeToString(mapSerializer, allStates)
                sessionCapture.captureInitialState(initialStateJson)
            } catch (e: Exception) {
                println("IntrospectionMiddleware: Failed to capture initial state - ${e.message}")
            }
        }

        val result = updatedState(action)

        if (sessionCapture.isStarted() && action !is IntrospectionAction) {
            try {
                val moduleName = result::class.qualifiedName ?: result::class.simpleName ?: "Unknown"
                val stateJson = json.encodeToString(
                    PolymorphicSerializer(ModuleState::class),
                    result
                )

                val capturedAction = CapturedAction(
                    clientId = config.clientId,
                    timestamp = Clock.System.now().toEpochMilliseconds(),
                    actionType = action::class.simpleName ?: "Unknown",
                    actionData = action.toString(),
                    stateDeltaJson = stateJson,
                    moduleName = moduleName
                )
                sessionCapture.captureAction(capturedAction)
            } catch (e: Exception) {
                println("IntrospectionMiddleware: Failed to capture action - ${e.message}")
            }
        }
    }
}
