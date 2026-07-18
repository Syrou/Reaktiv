package io.github.syrou.reaktiv.introspection.tooling

import io.github.syrou.reaktiv.core.Middleware
import io.github.syrou.reaktiv.core.StoreAccessor
import io.github.syrou.reaktiv.introspection.IntrospectionConfig
import io.github.syrou.reaktiv.introspection.PlatformContext
import io.github.syrou.reaktiv.introspection.capture.SessionCapture

public interface ToolingService {
    public val name: String
    public fun createMiddleware(): Middleware? = null
    public suspend fun start(context: ToolingServiceContext)
    public suspend fun stop()
    public suspend fun onCommand(command: ToolingCommand, args: Map<String, String>) {}
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
}
