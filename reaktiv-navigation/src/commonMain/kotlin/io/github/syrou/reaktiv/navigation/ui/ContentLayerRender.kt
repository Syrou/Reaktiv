package io.github.syrou.reaktiv.navigation.ui

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableStateMapOf
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
import io.github.syrou.reaktiv.navigation.util.determineContentAnimationDecision
import io.github.syrou.reaktiv.navigation.util.findLayoutGraphsInHierarchy
import io.github.syrou.reaktiv.navigation.util.shouldAnimateContentTransition
import kotlin.time.Clock


@Composable
fun ContentLayerRender(
    entries: List<NavigationEntry>,
    navigationState: NavigationState,
    screenContent: @Composable (Navigatable, StringAnyMap) -> Unit
) {
    val preservedContentEntry = remember { mutableStateOf<NavigationEntry?>(null) }

    val contentEntry = when {
        navigationState.isCurrentModal -> {
            preservedContentEntry.value ?: navigationState.underlyingScreen
        }
        navigationState.currentEntry.navigatable.renderLayer == RenderLayer.CONTENT -> {
            val currentContent = navigationState.currentEntry
            preservedContentEntry.value = currentContent
            currentContent
        }
        else -> entries.lastOrNull()
    }

    if (contentEntry == null) return

    val previousContentEntry = remember { mutableStateOf<NavigationEntry?>(null) }
    val wasShowingModal = remember { mutableStateOf(false) }
    val contentCache = remember { mutableStateMapOf<String, @Composable () -> Unit>() }
    val isModalStateChange = wasShowingModal.value != navigationState.isCurrentModal
    val isContentNavigation = !isModalStateChange &&
            previousContentEntry.value != null &&
            previousContentEntry.value != contentEntry

    val animationState = remember(contentEntry) {
        val prev = previousContentEntry.value
        if (isContentNavigation && prev != null) {
            val animationDecision = determineContentAnimationDecision(prev, contentEntry!!)
            if (animationDecision.hasAnyAnimation) {
                mutableStateOf(
                    NavigationAnimationState(
                        currentEntry = contentEntry!!,
                        previousEntry = prev,
                        isAnimating = true,
                        animationId = Clock.System.now().toEpochMilliseconds()
                    )
                )
            } else {
                mutableStateOf(
                    NavigationAnimationState(
                        currentEntry = contentEntry!!,
                        previousEntry = null,
                        isAnimating = false,
                        animationId = 0L
                    )
                )
            }
        } else {
            mutableStateOf(
                NavigationAnimationState(
                    currentEntry = contentEntry!!,
                    previousEntry = null,
                    isAnimating = false,
                    animationId = 0L
                )
            )
        }
    }

    LaunchedEffect(contentEntry) {
        if (contentEntry.navigatable.renderLayer == RenderLayer.CONTENT) {
            previousContentEntry.value = contentEntry
        }
    }

    LaunchedEffect(navigationState.isCurrentModal) {
        wasShowingModal.value = navigationState.isCurrentModal
    }
    
    // Update preserved content when modal's underlying screen changes
    LaunchedEffect(navigationState.underlyingScreen) {
        if (navigationState.isCurrentModal && navigationState.underlyingScreen != null) {
            // When a modal is shown and has an underlying screen, update preserved content
            preservedContentEntry.value = navigationState.underlyingScreen
        }
    }

    val currentContentKey = buildString {
        append(contentEntry.navigatable.route)
        append("_")
        append(contentEntry.graphId)
        append("_")
        append(contentEntry.stackPosition)
        append("_")
        append(contentEntry.params.hashCode())
    }

    if (!contentCache.containsKey(currentContentKey)) {
        contentCache[currentContentKey] = movableContentOf {
            key(currentContentKey) {
                screenContent(contentEntry.navigatable, contentEntry.params)
            }
        }
    }

    val previousEntry = animationState.value.previousEntry
    val previousContentKey = if (previousEntry != null) {
        buildString {
            append(previousEntry.navigatable.route)
            append("_")
            append(previousEntry.graphId)
            append("_")
            append(previousEntry.stackPosition)
            append("_")
            append(previousEntry.params.hashCode())
        }
    } else null

    if (previousEntry != null && previousContentKey != null && !contentCache.containsKey(previousContentKey)) {
        contentCache[previousContentKey] = movableContentOf {
            key(previousContentKey) {
                screenContent(previousEntry.navigatable, previousEntry.params)
            }
        }
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
                        contentCache = contentCache,
                        currentContentKey = currentContentKey,
                        previousContentKey = previousContentKey,
                        fallbackContent = screenContent,
                        onAnimationComplete = {
                            animationState.value = animationState.value.copy(
                                previousEntry = null,
                                isAnimating = false
                            )
                            // Clean up previous content from cache
                            if (previousContentKey != null) {
                                contentCache.remove(previousContentKey)
                            }
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
                contentCache = contentCache,
                currentContentKey = currentContentKey,
                previousContentKey = previousContentKey,
                fallbackContent = screenContent,
                onAnimationComplete = {
                    animationState.value = animationState.value.copy(
                        previousEntry = null,
                        isAnimating = false
                    )
                    if (previousContentKey != null) {
                        contentCache.remove(previousContentKey)
                    }
                }
            )
        }
    }
}