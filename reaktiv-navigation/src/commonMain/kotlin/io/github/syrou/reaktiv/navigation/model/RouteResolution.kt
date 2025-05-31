package io.github.syrou.reaktiv.navigation.model

import io.github.syrou.reaktiv.navigation.definition.NavigationGraph
import io.github.syrou.reaktiv.navigation.definition.Screen

data class RouteResolution(
    val targetScreen: Screen,
    val targetGraphId: String,
    val extractedParams: Map<String, Any>
)
