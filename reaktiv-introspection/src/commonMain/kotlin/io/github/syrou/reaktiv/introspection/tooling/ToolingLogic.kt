package io.github.syrou.reaktiv.introspection.tooling

import io.github.syrou.reaktiv.core.ExperimentalReaktivApi
import io.github.syrou.reaktiv.core.ModuleLogic
import io.github.syrou.reaktiv.core.Store
import io.github.syrou.reaktiv.core.StoreAccessor
import io.github.syrou.reaktiv.core.tracing.LogicTracer
import io.github.syrou.reaktiv.core.tracing.StateRead
import io.github.syrou.reaktiv.core.tracing.StateReadTracker
import io.github.syrou.reaktiv.core.util.ReaktivDebug
import io.github.syrou.reaktiv.introspection.CrashHandler
import io.github.syrou.reaktiv.introspection.IntrospectionConfig
import io.github.syrou.reaktiv.introspection.StallWatchdog
import io.github.syrou.reaktiv.introspection.PlatformContext
import io.github.syrou.reaktiv.introspection.SessionFileExport
import io.github.syrou.reaktiv.introspection.capture.SessionCapture
import io.github.syrou.reaktiv.introspection.tracing.IntrospectionLogicObserver
import kotlinx.coroutines.launch

@OptIn(ExperimentalReaktivApi::class)
public class ToolingLogic internal constructor(
    private val storeAccessor: StoreAccessor,
    private val config: IntrospectionConfig,
    private val platformContext: PlatformContext,
    private val capture: SessionCapture,
    private val services: List<ToolingService>
) : ModuleLogic() {

    private var logicObserver: IntrospectionLogicObserver? = null
    private var stateReadObserver: ((StateRead) -> Unit)? = null
    private var stallWatchdog: StallWatchdog? = null
    private val fileExport = SessionFileExport(platformContext)

    init {
        if (services.any { it.startsExternallyDriven }) {
            storeAccessor.asInternalOperations()?.markExternallyDriven()
        }
        if (config.enabled) {
            (storeAccessor as? Store)?.serializersModule?.let { capture.attachStateSerializers(it) }
            if (config.installLogicTracing) {
                logicObserver = IntrospectionLogicObserver(capture).also { LogicTracer.addObserver(it) }
            }
            stateReadObserver = { read: StateRead -> capture.captureStateRead(read) }
                .also { StateReadTracker.addObserver(it) }
            if (config.installCrashHandler) {
                CrashHandler(platformContext, capture).install()
            }
            if (config.installStallWatchdog && storeAccessor is Store) {
                stallWatchdog = StallWatchdog(
                    scope = storeAccessor,
                    thresholdMs = config.stallThresholdMs
                ).also {
                    if (!it.start()) {
                        stallWatchdog = null
                    }
                }
            }
            storeAccessor.launch {
                if (config.autoStart) {
                    startCapture()
                }
                services.forEach { service ->
                    val context = ToolingServiceContext(
                        storeAccessor = storeAccessor,
                        capture = capture,
                        config = config,
                        platformContext = platformContext,
                        serviceName = service.name,
                        statusSink = { name, status ->
                            storeAccessor.dispatch(ToolingAction.ServiceStatusChanged(name, status))
                        }
                    )
                    try {
                        service.start(context)
                    } catch (e: Exception) {
                        ReaktivDebug.warn("Tooling service ${service.name} failed to start: ${e.message}")
                        context.setStatus(ServiceStatus(ServiceState.DEGRADED, e.message))
                    }
                }
            }
        }
    }

    public suspend fun startCapture() {
        if (!capture.isStarted()) {
            capture.start(config.clientId, config.clientName, config.platform, config.clientMetadata)
        }
        storeAccessor.dispatch(ToolingAction.CaptureStateChanged(true))
    }

    public suspend fun stopCapture() {
        capture.stop()
        storeAccessor.dispatch(ToolingAction.CaptureStateChanged(false))
    }

    public fun service(name: String): ToolingService? = services.firstOrNull { it.name == name }

    public fun getSessionCapture(): SessionCapture = capture

    public suspend fun exportSessionJson(): String = capture.exportSession()

    public suspend fun exportCrashSessionJson(throwable: Throwable): String =
        capture.exportCrashSession(throwable)

    public suspend fun exportSessionToDownloads(fileName: String? = null): String {
        val json = capture.exportSession()
        return fileExport.saveToDownloads(json, fileName ?: capture.suggestFileName())
    }

    public suspend fun exportCrashSessionToDownloads(throwable: Throwable, fileName: String? = null): String {
        val json = capture.exportCrashSession(throwable)
        return fileExport.saveToDownloads(json, fileName ?: capture.suggestFileName("crash"))
    }

    override suspend fun beforeReset() {
        services.forEach { service ->
            try {
                service.stop()
            } catch (e: Exception) {
                ReaktivDebug.warn("Tooling service ${service.name} failed to stop: ${e.message}")
            }
        }
        capture.clear()
        logicObserver?.let { LogicTracer.removeObserver(it) }
        logicObserver = null
        stateReadObserver?.let { StateReadTracker.removeObserver(it) }
        stateReadObserver = null
        stallWatchdog?.stop()
        stallWatchdog = null
    }
}
