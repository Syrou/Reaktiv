package io.github.syrou.reaktiv.navigation.model

data class ModalAnimationState(
    val entry: NavigationEntry,
    val isEntering: Boolean,
    val isExiting: Boolean,
    val animationId: Long
)