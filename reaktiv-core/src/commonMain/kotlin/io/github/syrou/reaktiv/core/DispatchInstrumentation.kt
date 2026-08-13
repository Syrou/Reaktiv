package io.github.syrou.reaktiv.core

public fun interface DispatchStepDecorator {

    public fun decorate(
        name: String,
        step: suspend (ModuleAction) -> Unit
    ): suspend (ModuleAction) -> Unit
}

public interface DispatchInstrumentation {

    public val isActive: Boolean get() = true

    public suspend fun onDispatchStarted(
        action: ModuleAction,
        queueWaitMs: Long,
        queueDepth: Long
    ): String

    public fun onDispatchCompleted(token: String, applied: Boolean, durationMs: Long)

    public fun onDispatchFailed(token: String, error: Throwable, durationMs: Long)

    public suspend fun onDispatchDropped(action: ModuleAction)

    public suspend fun onExternalControlChanged(enabled: Boolean)

    public fun newDispatchDecorator(): DispatchStepDecorator? = null
}
