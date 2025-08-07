package io.github.syrou.reaktiv.navigation.ui

import androidx.compose.runtime.Composable
import io.github.syrou.reaktiv.core.serialization.StringAnyMap
import io.github.syrou.reaktiv.navigation.definition.Navigatable
import io.github.syrou.reaktiv.navigation.model.NavigationAnimationState
import io.github.syrou.reaktiv.navigation.model.NavigationEntry
import io.github.syrou.reaktiv.navigation.util.determineAnimationDecision

@Composable
fun RenderContentWithAnimation(
    animationState: NavigationAnimationState,
    screenWidth: Float,
    screenHeight: Float,
    navigatable: Navigatable,
    params: StringAnyMap,
    screenContent: @Composable (Navigatable, StringAnyMap) -> Unit,
    onAnimationComplete: () -> Unit
) {
    if (animationState.isAnimating && animationState.previousEntry != null) {
        val animationDecision = determineAnimationDecision(
            animationState.previousEntry,
            animationState.currentEntry
        )

        NavTransitionContainer(
            currentEntry = animationState.currentEntry,
            previousEntry = animationState.previousEntry,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            animationId = animationState.animationId,
            animationDecision = animationDecision,
            onAnimationComplete = onAnimationComplete
        ) { nav, p ->
            screenContent(nav, p)
        }
    } else {
        screenContent(navigatable, params)
    }
}