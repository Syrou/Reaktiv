package io.github.syrou.reaktiv.navigation.model

import androidx.compose.runtime.Immutable

@Immutable
data class NavigationAnimationState(
    val currentEntry: NavigationEntry,
    val previousEntry: NavigationEntry?,
    val isAnimating: Boolean,
    val animationId: Long
)