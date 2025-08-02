@file:OptIn(ExperimentalTime::class)

package io.github.syrou.reaktiv.navigation.ui

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import io.github.syrou.reaktiv.core.serialization.StringAnyMap
import io.github.syrou.reaktiv.core.util.ReaktivDebug
import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.definition.Navigatable
import io.github.syrou.reaktiv.navigation.model.NavigationAnimationState
import io.github.syrou.reaktiv.navigation.model.NavigationEntry
import io.github.syrou.reaktiv.navigation.transition.NavTransition
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

@Composable
fun NavigationContent(
    modifier: Modifier,
    navigationState: NavigationState,
    previousNavigationEntry: androidx.compose.runtime.MutableState<NavigationEntry?>,
    screenContent: @Composable (Navigatable, StringAnyMap) -> Unit
) {
    val currentEntry = navigationState.currentEntry
    val previousEntry = previousNavigationEntry.value

    val animationState = remember(currentEntry) {
        if (previousEntry != null && previousEntry != currentEntry) {
            val shouldAnimate = shouldAnimate(previousEntry, currentEntry)

            if (ReaktivDebug.isEnabled) {
                ReaktivDebug.nav("🔄 Navigation change detected:")
                ReaktivDebug.nav("  Previous: ${previousEntry.navigatable.route} (${previousEntry.graphId})")
                ReaktivDebug.nav("  Current: ${currentEntry.navigatable.route} (${currentEntry.graphId})")
                ReaktivDebug.nav("  Should animate: $shouldAnimate")
            }

            if (shouldAnimate) {
                mutableStateOf(
                    NavigationAnimationState(
                        currentEntry = currentEntry,
                        previousEntry = previousEntry,
                        isAnimating = true,
                        animationId = Clock.System.now().toEpochMilliseconds()
                    )
                )
            } else {
                mutableStateOf(
                    NavigationAnimationState(
                        currentEntry = currentEntry,
                        previousEntry = null,
                        isAnimating = false,
                        animationId = 0L
                    )
                )
            }
        } else {
            mutableStateOf(
                NavigationAnimationState(
                    currentEntry = currentEntry,
                    previousEntry = null,
                    isAnimating = false,
                    animationId = 0L
                )
            )
        }
    }

    LaunchedEffect(currentEntry) {
        previousNavigationEntry.value = currentEntry
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val screenWidth = maxWidth.value * LocalDensity.current.density
        val screenHeight = maxHeight.value * LocalDensity.current.density

        if (animationState.value.isAnimating && animationState.value.previousEntry != null) {
            if (ReaktivDebug.isEnabled) {
                ReaktivDebug.nav("🎬 Rendering animation transition")
            }

            NavTransitionContainer(
                currentEntry = animationState.value.currentEntry,
                previousEntry = animationState.value.previousEntry!!,
                screenWidth = screenWidth,
                screenHeight = screenHeight,
                animationId = animationState.value.animationId,
                onAnimationComplete = {
                    if (ReaktivDebug.isEnabled) {
                        ReaktivDebug.nav("✅ Animation completed, cleaning up state")
                    }
                    animationState.value = animationState.value.copy(
                        previousEntry = null,
                        isAnimating = false
                    )
                }
            ) { navigatable, params ->
                screenContent(navigatable, params)
            }
        } else {
            if (ReaktivDebug.isEnabled) {
                ReaktivDebug.nav("📺 Rendering static content (no animation)")
            }

            navigationState.visibleLayers.forEach { layer ->
                LayerContent(
                    layer = layer,
                    screenContent = screenContent
                )
            }
        }
    }
}

private fun shouldAnimate(
    previousEntry: NavigationEntry,
    currentEntry: NavigationEntry
): Boolean {
    if (previousEntry.stackPosition == 0) {
        return false
    }

    if (previousEntry.navigatable.route != currentEntry.navigatable.route) {
        val isForward = when {
            currentEntry.stackPosition > previousEntry.stackPosition -> true
            currentEntry.stackPosition < previousEntry.stackPosition -> false
            else -> {
                currentEntry.navigatable.route != previousEntry.navigatable.route
            }
        }

        val enterTransition = when {
            !isForward -> currentEntry.navigatable.popEnterTransition ?: currentEntry.navigatable.enterTransition
            else -> currentEntry.navigatable.enterTransition
        }

        val exitTransition = when {
            isForward -> previousEntry.navigatable.exitTransition
            else -> previousEntry.navigatable.popExitTransition ?: previousEntry.navigatable.exitTransition
        }

        val hasValidEnterTransition = enterTransition != NavTransition.None && enterTransition != NavTransition.Hold
        val hasValidExitTransition = exitTransition != NavTransition.None && exitTransition != NavTransition.Hold

        if (ReaktivDebug.isEnabled) {
            ReaktivDebug.nav("  Routes different: '${previousEntry.navigatable.route}' != '${currentEntry.navigatable.route}'")
            ReaktivDebug.nav("  Is forward: $isForward")
            ReaktivDebug.nav("  Enter transition: $enterTransition")
            ReaktivDebug.nav("  Exit transition: $exitTransition")
            ReaktivDebug.nav("  Has valid enter transition: $hasValidEnterTransition")
            ReaktivDebug.nav("  Has valid exit transition: $hasValidExitTransition")
        }

        return hasValidEnterTransition || hasValidExitTransition
    }

    return false
}