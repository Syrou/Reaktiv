package io.github.syrou.reaktiv.navigation

import io.github.syrou.reaktiv.core.serialization.StringAnyMap
import io.github.syrou.reaktiv.navigation.model.NavigationEntry
import kotlinx.serialization.Serializable

@Serializable
data class GraphState(
    val graphId: String,
    val currentEntry: NavigationEntry,
    val backStack: List<NavigationEntry>,
    val isActive: Boolean = false,
    val retainedState: StringAnyMap = emptyMap()
)