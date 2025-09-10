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
import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.definition.Navigatable
import io.github.syrou.reaktiv.navigation.layer.RenderLayer
import io.github.syrou.reaktiv.navigation.model.NavigationAnimationState
import io.github.syrou.reaktiv.navigation.model.NavigationEntry
import io.github.syrou.reaktiv.navigation.param.Params
import io.github.syrou.reaktiv.navigation.util.determineContentAnimationDecision
import io.github.syrou.reaktiv.navigation.util.findLayoutGraphsInHierarchy
import io.github.syrou.reaktiv.core.util.ReaktivDebug
import kotlin.time.Clock


@Composable
fun ContentLayerRender(
    entries: List<NavigationEntry>,
    navigationState: NavigationState,
    screenContent: @Composable (Navigatable, Params) -> Unit
) {
    ReaktivDebug.trace("🎨 ContentLayerRender recomposition - entries: ${entries.size}, isModal: ${navigationState.isCurrentModal}")
    val preservedContentEntry = remember { mutableStateOf<NavigationEntry?>(null) }

    val contentEntry = when {
        navigationState.isCurrentModal -> {
            preservedContentEntry.value ?: navigationState.underlyingScreen ?: navigationState.currentEntry
        }

        navigationState.currentEntry.navigatable.renderLayer == RenderLayer.CONTENT -> {
            val currentContent = navigationState.currentEntry
            preservedContentEntry.value = currentContent
            currentContent
        }

        else -> entries.lastOrNull() ?: navigationState.currentEntry
    }

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
            val animationDecision = determineContentAnimationDecision(prev, contentEntry)
            if (animationDecision.hasAnyAnimation) {
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
    ReaktivDebug.trace("🔑 ContentLayerRender current key: $currentContentKey")

    if (!contentCache.containsKey(currentContentKey)) {
        ReaktivDebug.trace("📱 Creating cached movableContent for key: $currentContentKey")
        contentCache[currentContentKey] = movableContentOf {
            key(currentContentKey) {
                ReaktivDebug.trace("🎬 Rendering cached content for: ${contentEntry.navigatable.route}")
                screenContent(contentEntry.navigatable, contentEntry.params)
            }
        }
    } else {
        ReaktivDebug.trace("♾️ Reusing cached movableContent for key: $currentContentKey")
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
    ReaktivDebug.trace("🔑 ContentLayerRender previous key: $previousContentKey")

    if (previousEntry != null && previousContentKey != null && !contentCache.containsKey(previousContentKey)) {
        ReaktivDebug.trace("📱 Creating cached movableContent for previous key: $previousContentKey")
        contentCache[previousContentKey] = movableContentOf {
            key(previousContentKey) {
                ReaktivDebug.trace("🎬 Rendering cached previous content for: ${previousEntry.navigatable.route}")
                screenContent(previousEntry.navigatable, previousEntry.params)
            }
        }
    }
    
    // Check for potential key conflicts
    if (currentContentKey == previousContentKey) {
        ReaktivDebug.trace("⚠️ CRITICAL: ContentLayerRender current and previous keys are identical: $currentContentKey")
    }
    ReaktivDebug.trace("💾 Content cache size: ${contentCache.size}, keys: ${contentCache.keys}")

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
                            ReaktivDebug.trace("🏁 ContentLayerRender animation complete - cleaning up")
                            animationState.value = animationState.value.copy(
                                previousEntry = null,
                                isAnimating = false
                            )
                            // Clean up previous content from cache
                            if (previousContentKey != null) {
                                ReaktivDebug.trace("🗑️ Removing previous content from cache: $previousContentKey")
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
                    ReaktivDebug.trace("🏁 ContentLayerRender animation complete (no layout) - cleaning up")
                    animationState.value = animationState.value.copy(
                        previousEntry = null,
                        isAnimating = false
                    )
                    if (previousContentKey != null) {
                        ReaktivDebug.trace("🗑️ Removing previous content from cache (no layout): $previousContentKey")
                        contentCache.remove(previousContentKey)
                    }
                }
            )
        }
    }
}