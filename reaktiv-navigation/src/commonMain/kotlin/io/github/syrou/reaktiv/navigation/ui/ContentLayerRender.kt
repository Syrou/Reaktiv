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
import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.definition.Navigatable
import io.github.syrou.reaktiv.navigation.layer.RenderLayer
import io.github.syrou.reaktiv.navigation.model.NavigationAnimationState
import io.github.syrou.reaktiv.navigation.model.NavigationEntry
import io.github.syrou.reaktiv.navigation.util.findLayoutGraphsInHierarchy
import io.github.syrou.reaktiv.navigation.util.shouldAnimateContentTransition
import kotlin.time.Clock

@Composable
fun ContentLayerRender(
    entries: List<NavigationEntry>,
    navigationState: NavigationState,
    screenContent: @Composable (Navigatable, StringAnyMap) -> Unit
) {
    val contentEntry = when {
        navigationState.isCurrentModal -> navigationState.underlyingScreen
        navigationState.currentEntry.navigatable.renderLayer == RenderLayer.CONTENT -> navigationState.currentEntry
        else -> entries.lastOrNull()
    }

    if (contentEntry == null) return

    val previousContentEntry = remember { mutableStateOf<NavigationEntry?>(null) }
    val wasShowingModal = remember { mutableStateOf(false) }

    // Track actual content navigation vs modal show/hide
    val isModalStateChange = wasShowingModal.value != navigationState.isCurrentModal
    val isContentNavigation = !isModalStateChange &&
            previousContentEntry.value != null &&
            previousContentEntry.value != contentEntry

    val animationState = remember(contentEntry, navigationState.isCurrentModal) {
        val prev = previousContentEntry.value
        // Only animate for actual content navigation, not modal show/hide
        if (isContentNavigation && prev != null && shouldAnimateContentTransition(prev, contentEntry)) {
            mutableStateOf(
                NavigationAnimationState(
                    currentEntry = contentEntry,
                    previousEntry = prev,
                    isAnimating = true,
                    animationId = Clock.System.now().toEpochMilliseconds()
                )
            )
        } else {
            mutableStateOf(
                NavigationAnimationState(
                    currentEntry = contentEntry,
                    previousEntry = null,
                    isAnimating = false,
                    animationId = 0L
                )
            )
        }
    }

    LaunchedEffect(contentEntry, navigationState.isCurrentModal) {
        // Only update previous content when it's actual content navigation
        if (!navigationState.isCurrentModal && contentEntry.navigatable.renderLayer == RenderLayer.CONTENT) {
            previousContentEntry.value = contentEntry
        }
        wasShowingModal.value = navigationState.isCurrentModal
    }

    val layoutGraphs = findLayoutGraphsInHierarchy(contentEntry.graphId, navigationState)

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val screenWidth = maxWidth.value * LocalDensity.current.density
        val screenHeight = maxHeight.value * LocalDensity.current.density

        if (layoutGraphs.isNotEmpty()) {
            RenderLayoutsHierarchically(
                layoutGraphs = layoutGraphs,
                modifier = Modifier.fillMaxSize(),
                navigationState = navigationState,
                previousNavigationEntry = previousContentEntry,
                screenContent = { navigatable, params ->
                    RenderContentWithAnimation(
                        animationState = animationState.value,
                        screenWidth = screenWidth,
                        screenHeight = screenHeight,
                        navigatable = navigatable,
                        params = params,
                        screenContent = screenContent,
                        onAnimationComplete = {
                            animationState.value = animationState.value.copy(
                                previousEntry = null,
                                isAnimating = false
                            )
                        }
                    )
                }
            )
        } else {
            RenderContentWithAnimation(
                animationState = animationState.value,
                screenWidth = screenWidth,
                screenHeight = screenHeight,
                navigatable = contentEntry.navigatable,
                params = contentEntry.params,
                screenContent = screenContent,
                onAnimationComplete = {
                    animationState.value = animationState.value.copy(
                        previousEntry = null,
                        isAnimating = false
                    )
                }
            )
        }
    }
}