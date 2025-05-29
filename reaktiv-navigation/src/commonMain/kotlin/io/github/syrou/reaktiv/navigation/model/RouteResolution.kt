package io.github.syrou.reaktiv.navigation.model

import io.github.syrou.reaktiv.navigation.definition.NavigationGraph
import io.github.syrou.reaktiv.navigation.definition.Screen

data class RouteResolution(
    val targetGraph: NavigationGraph,
    val targetScreen: Screen,
    val screenRoute: String,
    val extractedParams: Map<String, Any>,
    val requiresGraphSwitch: Boolean
)