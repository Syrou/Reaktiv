package io.github.syrou.reaktiv.navigation.model

import io.github.syrou.reaktiv.core.serialization.StringAnyMap
import io.github.syrou.reaktiv.navigation.definition.Screen
import kotlinx.serialization.Serializable

@Serializable
data class NavigationEntry(
    val screen: Screen,
    val params: StringAnyMap
)