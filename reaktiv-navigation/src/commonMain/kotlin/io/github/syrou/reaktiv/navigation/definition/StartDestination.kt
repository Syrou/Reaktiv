package io.github.syrou.reaktiv.navigation.definition

sealed class StartDestination {
    data class DirectScreen(val screen: Screen) : StartDestination()
    data class GraphReference(val graphId: String) : StartDestination()
}