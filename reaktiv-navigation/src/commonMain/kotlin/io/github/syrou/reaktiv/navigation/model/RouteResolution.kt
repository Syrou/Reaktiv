package io.github.syrou.reaktiv.navigation.model

import io.github.syrou.reaktiv.navigation.definition.Screen

data class RouteResolution(
    val targetScreen: Screen,
    val targetGraphId: String,
    val extractedParams: Map<String, Any>,
    val navigationGraphId: String? = null  // NEW: The graph that was actually navigated to
) {
    /**
     * Get the effective graph ID for navigation entry.
     * This ensures layouts are applied correctly when using startGraph references.
     */
    fun getEffectiveGraphId(): String = navigationGraphId ?: targetGraphId
}
