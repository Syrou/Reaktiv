package io.github.syrou.reaktiv.navigation.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.zIndex
import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.definition.NavigationGraph
import io.github.syrou.reaktiv.navigation.model.NavigationEntry
import io.github.syrou.reaktiv.navigation.util.findLayoutGraphsInHierarchy

/**
 * Layer types for unified rendering
 */
sealed class LayerType {
    object Content : LayerType()
    object GlobalOverlay : LayerType()
    object System : LayerType()
}

/**
 * Unified layer renderer that handles all navigation layer types consistently
 */
@Composable
fun UnifiedLayerRenderer(
    layerType: LayerType,
    entries: List<NavigationEntry>,
    navigationState: NavigationState,
    graphDefinitions: Map<String, NavigationGraph>
) {
    if (entries.isNotEmpty()) {
        when (layerType) {
            LayerType.Content -> ContentLayerRenderer(entries, navigationState, graphDefinitions)
            LayerType.GlobalOverlay -> OverlayLayerRenderer(entries)
            LayerType.System -> SystemLayerRenderer(entries, navigationState, graphDefinitions)
        }
    }
}

/**
 * Content layer renderer with animation support
 *
 * Manages screen transitions by keeping current and previous screens composed simultaneously.
 * Previous entry is tracked locally in Compose and cleared after animation duration.
 */
@Composable
private fun ContentLayerRenderer(
    entries: List<NavigationEntry>,
    navigationState: NavigationState,
    graphDefinitions: Map<String, NavigationGraph>
) {
    val currentEntry = entries.lastOrNull() ?: navigationState.currentEntry

    // Get animation state managing current + previous entries
    val animationState = rememberLayerAnimationState(
        entries = listOf(currentEntry)
    )

    // Apply layout hierarchy for proper nesting
    val layoutGraphs = findLayoutGraphsInHierarchy(currentEntry.graphId, graphDefinitions)
    ApplyLayoutsHierarchy(layoutGraphs) {
        ContentRenderer(animationState)
    }
}

/**
 * Overlay layer renderer for modals with complex animation states
 */
@Composable
private fun OverlayLayerRenderer(
    entries: List<NavigationEntry>
) {
    val modalStates = rememberModalAnimationState(entries)
    val activeStates = remember { mutableStateMapOf<String, ModalEntryState>() }

    // Update active states
    modalStates.forEach { state ->
        activeStates[state.entry.stableKey] = state
    }

    Box(modifier = Modifier.fillMaxSize()) {
        val windowInfo = LocalWindowInfo.current
        val screenWidth = windowInfo.containerSize.width.toFloat()
        val screenHeight = windowInfo.containerSize.height.toFloat()

        activeStates.values
            .sortedBy { it.entry.navigatable.elevation }
            .forEach { modalState ->
                key(modalState.entry.stableKey) {
                    NavigationAnimations.AnimatedEntry(
                        entry = modalState.entry,
                        animationType = modalState.animationType,
                        animationDecision = null, // Modals manage their own transitions
                        screenWidth = screenWidth,
                        screenHeight = screenHeight,
                        zIndex = 2000f + modalState.entry.navigatable.elevation,
                        onAnimationComplete = {
                            val completed = modalState.markCompleted()
                            if (completed != null) {
                                activeStates[modalState.entry.stableKey] = completed
                            } else {
                                activeStates.remove(modalState.entry.stableKey)
                            }
                        }
                    ) {
                        modalState.entry.navigatable.Content(modalState.entry.params)
                    }
                }
            }
    }
}

/**
 * System layer renderer for simple overlays
 */
@Composable
private fun SystemLayerRenderer(
    entries: List<NavigationEntry>,
    navigationState: NavigationState,
    graphDefinitions: Map<String, NavigationGraph>
) {
    entries
        .sortedBy { it.navigatable.elevation }
        .forEach { entry ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(3000f + entry.navigatable.elevation)
            ) {
                entry.navigatable.Content(entry.params)
            }
        }
}

/**
 * Content renderer with screen transition animations
 *
 * Keeps both current and previous screens composed simultaneously.
 * Only these two screens participate in animations, controlled via zIndex.
 * Animation timing is managed by NavigationLogic, not by animation completion callbacks.
 */
@Composable
private fun ContentRenderer(animationState: LayerAnimationState) {

    val windowInfo = LocalWindowInfo.current
    val screenWidth = windowInfo.containerSize.width.toFloat()
    val screenHeight = windowInfo.containerSize.height.toFloat()

    // Determine zIndex ordering based on animation requirements
    val shouldExitBeOnTop = animationState.animationDecision?.let { decision ->
        decision.enterTransition is io.github.syrou.reaktiv.navigation.transition.NavTransition.None &&
                decision.exitTransition !is io.github.syrou.reaktiv.navigation.transition.NavTransition.None
    } ?: false

    Box(modifier = Modifier.fillMaxSize()) {
        // Render current and previous entries (max 2 screens)
        animationState.aliveEntries.forEach { entry ->
            val isCurrentScreen = entry.stableKey == animationState.currentEntry.stableKey
            val isPreviousScreen = entry.stableKey == animationState.previousEntry?.stableKey

            key(entry.stableKey) {
                val zIndex = when {
                    isCurrentScreen -> if (shouldExitBeOnTop) 2f else 3f
                    isPreviousScreen -> if (shouldExitBeOnTop) 3f else 2f
                    else -> 1f
                }

                NavigationAnimations.AnimatedEntry(
                    entry = entry,
                    animationType = if (isCurrentScreen)
                        NavigationAnimations.AnimationType.SCREEN_ENTER
                    else
                        NavigationAnimations.AnimationType.SCREEN_EXIT,
                    animationDecision = animationState.animationDecision,
                    screenWidth = screenWidth,
                    screenHeight = screenHeight,
                    zIndex = zIndex,
                    onAnimationComplete = null  // Timing managed by NavigationLogic
                ) {
                    entry.navigatable.Content(entry.params)
                }
            }
        }
    }
}

/**
 * Applies layout hierarchy composition using foldRight for proper nesting order
 */
@Composable
private fun ApplyLayoutsHierarchy(
    layoutGraphs: List<NavigationGraph>,
    content: @Composable () -> Unit
) {
    if (layoutGraphs.isEmpty()) {
        content()
    } else {
        // Use foldRight to create proper nested structure: outermost layout first
        layoutGraphs.foldRight(content) { graph, acc ->
            @Composable {
                graph.layout?.invoke { acc() } ?: acc()
            }
        }.invoke()
    }
}
