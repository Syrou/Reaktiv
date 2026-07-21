package io.github.syrou.reaktiv.introspection.tooling

import io.github.syrou.reaktiv.core.ExternalControlExempt
import io.github.syrou.reaktiv.core.Middleware
import io.github.syrou.reaktiv.core.ModuleAction
import io.github.syrou.reaktiv.core.ModuleState
import io.github.syrou.reaktiv.core.ModuleWithLogic
import io.github.syrou.reaktiv.core.Store
import io.github.syrou.reaktiv.core.StoreAccessor
import io.github.syrou.reaktiv.core.util.selectLogic
import io.github.syrou.reaktiv.introspection.IntrospectionConfig
import io.github.syrou.reaktiv.introspection.PlatformContext
import io.github.syrou.reaktiv.introspection.resolveRedactor
import io.github.syrou.reaktiv.introspection.capture.SessionCapture
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Serializable
public data class ToolingState(
    val isCapturing: Boolean = false,
    val services: Map<String, ServiceStatus> = emptyMap()
) : ModuleState

@Serializable
public data class ServiceStatus(val state: ServiceState, val detail: String? = null)

@Serializable
public enum class ServiceState { STARTING, RUNNING, DEGRADED, STOPPED }

@Serializable
public sealed class ToolingAction : ModuleAction(ToolingModule::class), ExternalControlExempt {
    @Serializable
    public data class ServiceStatusChanged(val service: String, val status: ServiceStatus) : ToolingAction()

    @Serializable
    public data class CaptureStateChanged(val isCapturing: Boolean) : ToolingAction()

    public data class ServiceCommand(
        val service: String,
        val command: ToolingCommand,
        val args: Map<String, String> = emptyMap()
    ) : ToolingAction()
}

public interface ToolingCommand

public class ToolingModuleBuilder internal constructor() {
    internal val services = mutableListOf<ToolingService>()

    public fun install(service: ToolingService) {
        services.add(service)
    }
}

public fun createToolingModule(
    config: IntrospectionConfig,
    platformContext: PlatformContext,
    block: ToolingModuleBuilder.() -> Unit = {}
): ToolingModule {
    val builder = ToolingModuleBuilder().apply(block)
    return ToolingModule(config, platformContext, builder.services.toList())
}

public class ToolingModule internal constructor(
    private val config: IntrospectionConfig,
    private val platformContext: PlatformContext,
    private val services: List<ToolingService>
) : ModuleWithLogic<ToolingState, ToolingAction, ToolingLogic> {

    internal val capture: SessionCapture = SessionCapture(
        maxActions = config.maxActions,
        maxLogicEvents = config.maxLogicEvents,
        redactor = config.resolveRedactor()
    )

    override val initialState: ToolingState = ToolingState()

    override val reducer: (ToolingState, ToolingAction) -> ToolingState = { state, action ->
        when (action) {
            is ToolingAction.ServiceStatusChanged ->
                state.copy(services = state.services + (action.service to action.status))
            is ToolingAction.CaptureStateChanged ->
                state.copy(isCapturing = action.isCapturing)
            is ToolingAction.ServiceCommand -> state
        }
    }

    override val createLogic: (StoreAccessor) -> ToolingLogic = { storeAccessor ->
        ToolingLogic(storeAccessor, config, platformContext, capture, services)
    }

    override val createMiddleware: (() -> Middleware) = {
        val chain = services.mapNotNull { it.createMiddleware() } +
            commandRoutingMiddleware(services) +
            captureMiddleware(config, capture)
        composeMiddlewares(chain)
    }
}

internal fun commandRoutingMiddleware(services: List<ToolingService>): Middleware =
    { action, _, storeAccessor, updatedState ->
        if (action is ToolingAction.ServiceCommand) {
            services.firstOrNull { it.name == action.service }?.let { service ->
                storeAccessor.launch {
                    service.onCommand(action.command, action.args)
                }
            }
        }
        updatedState(action)
    }

internal fun captureMiddleware(config: IntrospectionConfig, capture: SessionCapture): Middleware {
    var initialized = false
    var initialStateCaptured = false
    return middleware@{ action, getAllStates, storeAccessor, updatedState ->
        if (!config.enabled) {
            updatedState(action)
            return@middleware
        }
        if (!initialized) {
            initialized = true
            (storeAccessor as? Store)?.serializersModule?.let {
                capture.attachStateSerializers(it)
            }
        }
        if (!initialStateCaptured && capture.isStarted() && action !is ToolingAction) {
            initialStateCaptured = true
            capture.captureInitialState(getAllStates())
        }
        val result = updatedState(action)
        if (capture.isStarted() && action !is ToolingAction) {
            capture.captureDispatchedAction(action, result)
        }
    }
}

internal fun composeMiddlewares(middlewares: List<Middleware>): Middleware {
    if (middlewares.size == 1) return middlewares.first()
    return { action, getAllStates, storeAccessor, updatedState ->
        var invoke: suspend (ModuleAction) -> ModuleState? = { a -> updatedState(a) }
        for (middleware in middlewares.asReversed()) {
            val next = invoke
            invoke = { a ->
                var result: ModuleState? = null
                middleware(a, getAllStates, storeAccessor) { inner ->
                    val produced = checkNotNull(next(inner)) {
                        "only the outermost tooling middleware may block an action"
                    }
                    result = produced
                    produced
                }
                result
            }
        }
        invoke(action)
    }
}

public suspend fun StoreAccessor.toolingService(name: String): ToolingService? =
    selectLogic<ToolingLogic>().service(name)
