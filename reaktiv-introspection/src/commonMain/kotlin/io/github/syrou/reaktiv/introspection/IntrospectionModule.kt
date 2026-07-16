package io.github.syrou.reaktiv.introspection

import io.github.syrou.reaktiv.core.util.currentTimeMillis
import io.github.syrou.reaktiv.core.Middleware
import io.github.syrou.reaktiv.core.ModuleAction
import io.github.syrou.reaktiv.core.ModuleLogic
import io.github.syrou.reaktiv.core.ModuleState
import io.github.syrou.reaktiv.core.ModuleWithLogic
import io.github.syrou.reaktiv.core.Store
import io.github.syrou.reaktiv.core.StoreAccessor
import io.github.syrou.reaktiv.core.tracing.LogicTracer
import io.github.syrou.reaktiv.introspection.capture.SessionCapture
import io.github.syrou.reaktiv.introspection.tracing.IntrospectionLogicObserver
import kotlinx.serialization.Serializable

/**
 * State for introspection module.
 * Minimal state - just tracks if capture is active.
 */
@Serializable
public data class IntrospectionState(
    val isCapturing: Boolean = false
) : ModuleState

/**
 * Actions for introspection module.
 */
@Serializable
public sealed class IntrospectionAction : ModuleAction(IntrospectionModule::class)

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
public class IntrospectionLogic internal constructor(
    private val storeAccessor: StoreAccessor,
    private val config: IntrospectionConfig,
    private val sessionCapture: SessionCapture,
    platformContext: PlatformContext
) : ModuleLogic() {

    private var logicObserver: IntrospectionLogicObserver? = null
    private val sessionFileExport = SessionFileExport(platformContext)

    init {
        if (config.enabled) {
            if (!sessionCapture.isStarted()) {
                sessionCapture.start(config.clientId, config.clientName, config.platform)
            }
            logicObserver = IntrospectionLogicObserver(sessionCapture)
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

    public fun getSessionCapture(): SessionCapture = sessionCapture

    /**
     * Exports the current session as a JSON string.
     *
     * @return JSON string that can be imported as a ghost device in DevTools
     */
    public suspend fun exportSessionJson(): String = sessionCapture.exportSession()

    /**
     * Exports the current session with crash information.
     *
     * @param throwable The exception that caused the crash
     * @return JSON string with crash info
     */
    public suspend fun exportCrashSessionJson(throwable: Throwable): String =
        sessionCapture.exportCrashSession(throwable)

    /**
     * Exports the current session to the device's Downloads folder.
     *
     * @param fileName Optional custom file name (defaults to timestamped name)
     * @return Path where the file was saved
     */
    public suspend fun exportSessionToDownloads(fileName: String? = null): String {
        val json = sessionCapture.exportSession()
        val actualFileName = fileName
            ?: "reaktiv_session_${currentTimeMillis()}.json"
        return sessionFileExport.saveToDownloads(json, actualFileName)
    }

    /**
     * Exports the current session with crash information to the device's Downloads folder.
     *
     * @param throwable The exception that caused the crash
     * @param fileName Optional custom file name (defaults to timestamped name)
     * @return Path where the file was saved
     */
    public suspend fun exportCrashSessionToDownloads(throwable: Throwable, fileName: String? = null): String {
        val json = sessionCapture.exportCrashSession(throwable)
        val actualFileName = fileName
            ?: "reaktiv_crash_${currentTimeMillis()}.json"
        return sessionFileExport.saveToDownloads(json, actualFileName)
    }

    /**
     * Cleans up resources.
     */
    public suspend fun cleanup() {
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
public class IntrospectionModule(
    private val config: IntrospectionConfig,
    private val sessionCapture: SessionCapture,
    private val platformContext: PlatformContext
) : ModuleWithLogic<IntrospectionState, IntrospectionAction, IntrospectionLogic> {

    override val initialState: IntrospectionState = IntrospectionState(isCapturing = config.enabled)

    override val reducer: (IntrospectionState, IntrospectionAction) -> IntrospectionState = { state, _ -> state }

    override val createLogic: (StoreAccessor) -> IntrospectionLogic = { storeAccessor ->
        IntrospectionLogic(storeAccessor, config, sessionCapture, platformContext)
    }

    override val createMiddleware: (() -> Middleware) = {
        IntrospectionMiddleware(config, sessionCapture).middleware
    }
}

/**
 * Middleware that captures all dispatched actions.
 *
 * Capture calls only enqueue records on the shared [SessionCapture]; JSON encoding
 * and storage writes happen on the capture worker, off the dispatch path.
 */
internal class IntrospectionMiddleware(
    private val config: IntrospectionConfig,
    private val sessionCapture: SessionCapture
) {
    private var initialized = false
    private var initialStateCaptured = false

    val middleware: Middleware = middleware@{ action, getAllStates, storeAccessor, updatedState ->
        if (!config.enabled) {
            updatedState(action)
            return@middleware
        }

        if (!initialized) {
            initialized = true
            (storeAccessor as? Store)?.serializersModule?.let {
                sessionCapture.attachStateSerializers(it)
            }
        }

        if (!initialStateCaptured && sessionCapture.isStarted() && action !is IntrospectionAction) {
            initialStateCaptured = true
            sessionCapture.captureInitialState(getAllStates())
        }

        val result = updatedState(action)

        if (sessionCapture.isStarted() && action !is IntrospectionAction) {
            sessionCapture.captureDispatchedAction(action, result)
        }
    }
}
