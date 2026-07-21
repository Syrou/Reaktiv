package io.github.syrou.reaktiv.introspection.tooling

import io.github.syrou.reaktiv.core.Middleware
import io.github.syrou.reaktiv.core.StoreAccessor
import io.github.syrou.reaktiv.introspection.IntrospectionConfig
import io.github.syrou.reaktiv.introspection.PlatformContext
import io.github.syrou.reaktiv.introspection.capture.SessionCapture

public interface ToolingService {
    public val name: String

    /**
     * Whether this service knows at construction time that the store will be driven by a
     * remote publisher.
     *
     * When any installed service returns `true`, [ToolingLogic] engages the store's dispatch
     * gate synchronously, before module logic can begin start-up work. A service that reports
     * `true` is responsible for calling
     * [io.github.syrou.reaktiv.core.InternalStoreOperations.endExternalControl] if the
     * replication it expected never materializes, otherwise the store stays gated with no
     * source of state.
     */
    public val startsExternallyDriven: Boolean get() = false

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
