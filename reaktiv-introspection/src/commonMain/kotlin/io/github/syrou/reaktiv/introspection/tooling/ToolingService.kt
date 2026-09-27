package io.github.syrou.reaktiv.introspection.tooling

import io.github.syrou.reaktiv.core.Middleware
import io.github.syrou.reaktiv.core.StoreAccessor
import io.github.syrou.reaktiv.introspection.IntrospectionConfig
import io.github.syrou.reaktiv.introspection.PlatformContext
import io.github.syrou.reaktiv.introspection.capture.SessionCapture
import kotlinx.serialization.json.JsonElement

public interface ToolingService {
    public val name: String

    /**
     * Whether this service knows at construction time that the store will be driven by a
     * remote publisher.
     *
     * When any installed service returns `true`, the store engages its dispatch gate before any
     * module logic is created, so no start-up work begins. A service that reports `true` is
     * responsible for calling [io.github.syrou.reaktiv.core.ExternalStateAccess.endControl] if the
     * replication it expected never materializes, otherwise the store stays gated with no
     * source of state.
     */
    public val startsExternallyDriven: Boolean get() = false

    public fun createMiddleware(): Middleware? = null
    public suspend fun start(context: ToolingServiceContext)
    public suspend fun stop()
    public suspend fun onCommand(command: ToolingCommand, args: Map<String, String>) {}
    public suspend fun onRequest(request: String, payload: JsonElement): JsonElement? = null
}

public class ToolingServiceContext internal constructor(
    public val storeAccessor: StoreAccessor,
    public val capture: SessionCapture,
    public val config: IntrospectionConfig,
    public val platformContext: PlatformContext,
    private val serviceName: String,
    private val statusSink: suspend (String, ServiceStatus) -> Unit
) {
    public suspend fun setStatus(status: ServiceStatus) {
        statusSink(serviceName, status)
    }

    public fun publishExtension(key: String, value: JsonElement) {
        capture.putExtension(key, value)
    }
}
