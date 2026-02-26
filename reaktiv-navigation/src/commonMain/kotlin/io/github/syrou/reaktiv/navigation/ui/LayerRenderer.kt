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
import io.github.syrou.reaktiv.navigation.definition.Modal
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

private object NavigationZIndex {
    const val CONTENT_BACK = 2f
    const val CONTENT_FRONT = 3f
    const val CONTENT_LIFTED_EXIT = 100f
    const val GLOBAL_OVERLAY_BASE = 2000f
    const val SYSTEM_BASE = 9001f
}

/**
 * Unified layer renderer that handles all navigation layer types consistently
 */
@Composable
fun UnifiedLayerRenderer(
    layerType: LayerType,
    entries: List<NavigationEntry>,
    graphDefinitions: Map<String, NavigationGraph>
) {
    if (entries.isNotEmpty()) {
        when (layerType) {
            LayerType.Content -> ContentLayerRenderer(entries, graphDefinitions)
            LayerType.GlobalOverlay -> OverlayLayerRenderer(entries)
            LayerType.System -> SystemLayerRenderer(entries)
        }
    }
}

/**
 * Content layer renderer with animation support
 *
 * Manages screen transitions by keeping current and previous screens composed simultaneously.
 * Previous entry is tracked locally in Compose and cleared after animation duration.
 *
 * When the layout hierarchy changes between screens (e.g. navigating out of a sub-graph),
 * two strategies handle the exiting screen:
 *
 * INSIDE: When shared layout chrome exists (e.g. HomeNavigationScaffold). The shared chrome
 * renders once and stays static. Inside its content slot, the entering screen and the animated
 * exiting screen (wrapped in its unique layouts like ProjectTabLayout) coexist at different
 * zIndex values. Only the unique layouts animate; shared chrome stays fixed.
 *
 * OUTSIDE: When no shared chrome exists (e.g. login → projects). The exiting screen is lifted
 * outside the incoming layout hierarchy entirely, placed at the top level at zIndex=100.
 */
@Composable
private fun ContentLayerRenderer(
    entries: List<NavigationEntry>,
    graphDefinitions: Map<String, NavigationGraph>
) {
    val currentEntry = entries.last()

    val animationState = rememberLayerAnimationState(currentEntry)

    val layoutGraphs = findLayoutGraphsInHierarchy(currentEntry.graphId, graphDefinitions)
    val prevEntry = animationState.previousEntry
    val prevLayoutGraphs = prevEntry?.let { findLayoutGraphsInHierarchy(it.graphId, graphDefinitions) }
    val layoutChanged = prevLayoutGraphs != null &&
        prevLayoutGraphs.map { it.route } != layoutGraphs.map { it.route }
    val shouldLiftExiting = layoutChanged && (animationState.animationDecision?.shouldAnimateExit ?: false)

    val prevRoutes = prevLayoutGraphs?.map { it.route }?.toSet() ?: emptySet()
    val currentRoutes = layoutGraphs.map { it.route }.toSet()
    val sharedRoutes = prevRoutes.intersect(currentRoutes)

    val sharedLayouts = layoutGraphs.filter { it.route in sharedRoutes }
    val currentUniqueLayouts = layoutGraphs.filter { it.route !in sharedRoutes }
    val prevUniqueLayouts = prevLayoutGraphs?.filter { it.route !in sharedRoutes } ?: emptyList()

    val useInsideStrategy = shouldLiftExiting && sharedLayouts.isNotEmpty()
    val useOutsideStrategy = shouldLiftExiting && sharedLayouts.isEmpty()


    val windowInfo = LocalWindowInfo.current

    Box(modifier = Modifier.fillMaxSize()) {
        if (useInsideStrategy && prevEntry != null) {
            ApplyLayoutsHierarchy(sharedLayouts) {
                Box(modifier = Modifier.fillMaxSize()) {
                    ApplyLayoutsHierarchy(currentUniqueLayouts) {
                        ContentRenderer(
                            animationState.copy(
                                aliveEntries = listOf(animationState.currentEntry),
                                previousEntry = null,
                                animationDecision = null
                            )
                        )
                    }
                    key(prevEntry.stableKey) {
                        NavigationAnimations.AnimatedEntry(
                            entry = prevEntry,
                            animationType = NavigationAnimations.AnimationType.SCREEN_EXIT,
                            animationDecision = animationState.animationDecision,
                            screenWidth = windowInfo.containerSize.width.toFloat(),
                            screenHeight = windowInfo.containerSize.height.toFloat(),
                            zIndex = NavigationZIndex.CONTENT_LIFTED_EXIT,
                            onAnimationComplete = null
                        ) {
                            ApplyLayoutsHierarchy(prevUniqueLayouts) {
                                prevEntry.navigatable.Content(prevEntry.params)
                            }
                        }
                    }
                }
            }
        } else {
            ApplyLayoutsHierarchy(layoutGraphs) {
                val innerState = if (useOutsideStrategy && prevEntry != null) {
                    animationState.copy(
                        aliveEntries = listOf(animationState.currentEntry),
                        previousEntry = null,
                        animationDecision = null
                    )
                } else {
                    animationState
                }
                ContentRenderer(innerState)
            }
            if (useOutsideStrategy && prevEntry != null) {
                key(prevEntry.stableKey) {
                    NavigationAnimations.AnimatedEntry(
                        entry = prevEntry,
                        animationType = NavigationAnimations.AnimationType.SCREEN_EXIT,
                        animationDecision = animationState.animationDecision,
                        screenWidth = windowInfo.containerSize.width.toFloat(),
                        screenHeight = windowInfo.containerSize.height.toFloat(),
                        zIndex = 100f,
                        onAnimationComplete = null
                    ) {
                        ApplyLayoutsHierarchy(prevLayoutGraphs ?: emptyList()) {
                            prevEntry.navigatable.Content(prevEntry.params)
                        }
                    }
                }
            }
        }
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
                        animationDecision = null,
                        screenWidth = screenWidth,
                        screenHeight = screenHeight,
                        zIndex = NavigationZIndex.GLOBAL_OVERLAY_BASE + modalState.entry.navigatable.elevation,
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
 * System layer renderer for top-level overlays.
 *
 * Modal entries are rendered via [NavigationAnimations.AnimatedEntry] so they receive
 * the standard dimmer background and tap-outside dismiss support. Non-modal entries
 * (e.g. full-screen loading overlays) are rendered as plain Boxes.
 */
@Composable
private fun SystemLayerRenderer(
    entries: List<NavigationEntry>
) {
    val windowInfo = LocalWindowInfo.current
    val screenWidth = windowInfo.containerSize.width.toFloat()
    val screenHeight = windowInfo.containerSize.height.toFloat()

    entries
        .sortedBy { it.navigatable.elevation }
        .forEach { entry ->
            key(entry.stableKey) {
                if (entry.navigatable is Modal) {
                    NavigationAnimations.AnimatedEntry(
                        entry = entry,
                        animationType = NavigationAnimations.AnimationType.MODAL_ENTER,
                        animationDecision = null,
                        screenWidth = screenWidth,
                        screenHeight = screenHeight,
                        zIndex = NavigationZIndex.SYSTEM_BASE + entry.navigatable.elevation,
                        onAnimationComplete = null
                    ) {
                        entry.navigatable.Content(entry.params)
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .zIndex(9001f + entry.navigatable.elevation)
                    ) {
                        entry.navigatable.Content(entry.params)
                    }
                }
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

    val shouldExitBeOnTop = animationState.animationDecision?.let { decision ->
        decision.enterTransition is io.github.syrou.reaktiv.navigation.transition.NavTransition.None &&
                decision.exitTransition !is io.github.syrou.reaktiv.navigation.transition.NavTransition.None
    } ?: false

    Box(modifier = Modifier.fillMaxSize()) {
        animationState.aliveEntries.forEach { entry ->
            val isCurrentScreen = entry.stableKey == animationState.currentEntry.stableKey
            val isPreviousScreen = entry.stableKey == animationState.previousEntry?.stableKey

            key(entry.stableKey) {
                val zIndex = when {
                    isCurrentScreen -> if (shouldExitBeOnTop) NavigationZIndex.CONTENT_BACK else NavigationZIndex.CONTENT_FRONT
                    isPreviousScreen -> if (shouldExitBeOnTop) NavigationZIndex.CONTENT_FRONT else NavigationZIndex.CONTENT_BACK
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
                    onAnimationComplete = null
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
        layoutGraphs.foldRight(content) { graph, acc ->
            @Composable {
                graph.layout?.invoke { acc() } ?: acc()
            }
        }.invoke()
    }
}
