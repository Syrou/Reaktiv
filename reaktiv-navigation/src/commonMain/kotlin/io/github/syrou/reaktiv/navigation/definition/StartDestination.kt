package io.github.syrou.reaktiv.navigation.definition

import kotlinx.serialization.Serializable

@Serializable
public sealed class StartDestination {
    @Serializable
    public data class DirectScreen(val screen: Screen) : StartDestination()
    @Serializable
    public data class GraphReference(val graphId: String) : StartDestination()
}
