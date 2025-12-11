package io.github.syrou.reaktiv.navigation.definition

import kotlinx.serialization.Serializable

@Serializable
sealed class StartDestination {
    @Serializable
    data class DirectScreen(val screen: Screen) : StartDestination()
    @Serializable
    data class GraphReference(val graphId: String) : StartDestination()
}