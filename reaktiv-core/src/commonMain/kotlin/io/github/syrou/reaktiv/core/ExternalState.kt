package io.github.syrou.reaktiv.core

public enum class ExternalStatePolicy { Deny, OnRequest, Allow }

public interface ExternalStateRequester {
    public fun startsUnderExternalControl(): Boolean = false
}

public sealed class HydrateSource(public val label: String) {
    public data object Restore : HydrateSource("Persistence")
    public data object Replication : HydrateSource("Replication")
    public data class External(val origin: String) : HydrateSource(origin)
}

public interface ExternalStateAccess {
    public val isUnderControl: Boolean
    public suspend fun hydrate(states: Map<String, ModuleState>, source: HydrateSource): DispatchResult
    public suspend fun beginControl()
    public suspend fun endControl()
}
