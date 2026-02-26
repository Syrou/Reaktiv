package io.github.syrou.reaktiv.navigation.model

import kotlinx.serialization.Serializable

@Serializable
data class ModalContext(
    val modalEntry: NavigationEntry,
    val originalUnderlyingScreenEntry: NavigationEntry,
    val navigatedAwayToRoute: String? = null
)
