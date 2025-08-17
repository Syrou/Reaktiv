package io.github.syrou.reaktiv.navigation.definition

import kotlinx.serialization.Serializable

/**
 * GuidedFlow coordinator - not a navigatable, just an identifier for flow coordination
 */
@Serializable
data class GuidedFlow(
    val route: String
)