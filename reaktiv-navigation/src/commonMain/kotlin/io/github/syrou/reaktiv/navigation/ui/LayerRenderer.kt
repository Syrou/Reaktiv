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
import io.github.syrou.reaktiv.navigation.layer.RenderLayer
import io.github.syrou.reaktiv.navigation.model.NavigationEntry
import io.github.syrou.reaktiv.navigation.transition.computeBackGesturePlan
import io.github.syrou.reaktiv.navigation.transition.computeDismissGesturePlan
import io.github.syrou.reaktiv.navigation.util.findLayoutGraphsInHierarchy

internal class ContentScrubPreview(
    val revealedEntry: NavigationEntry?,
    val topDriver: TransitionProgressDriver.External,
    val revealedDriver: TransitionProgressDriver.External
)

internal object NavigationZIndex {
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
    layerType: RenderLayer,
    entries: List<NavigationEntry>,
    graphDefinitions: Map<String, NavigationGraph>
) {
    if (entries.isNotEmpty()) {
        when (layerType) {
            RenderLayer.CONTENT -> ContentLayerRenderer(entries, graphDefinitions)
            RenderLayer.GLOBAL_OVERLAY -> OverlayLayerRenderer(entries)
            RenderLayer.SYSTEM -> SystemLayerRenderer(entries)
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
    val navModule = LocalNavigationModule.current
    val currentEntry = entries.last()

    val animationState = rememberLayerAnimationState(currentEntry)

    val interactiveController = LocalInteractiveTransitionController.current
    val activeKind = interactiveController?.scrubKind
    val windowInfoForScrub = LocalWindowInfo.current
    val scrubPreview: ContentScrubPreview? = if (
        interactiveController != null &&
        interactiveController.phase != InteractiveTransitionController.Phase.Idle
    ) {
        when (activeKind) {
            is InteractiveTransitionController.ScrubKind.ContentBack -> {
                if (activeKind.topEntry.stableKey == currentEntry.stableKey) {
                    val topNavigatable = navModule.resolveNavigatable(activeKind.topEntry)
                    val revealedNavigatable = navModule.resolveNavigatable(activeKind.revealedEntry)
                    if (topNavigatable != null && revealedNavigatable != null) {
                        val width = windowInfoForScrub.containerSize.width.toFloat()
                        val height = windowInfoForScrub.containerSize.height.toFloat()
                        val plan = remember(
                            activeKind.topEntry.stableKey,
                            activeKind.revealedEntry.stableKey,
                            width,
                            height
                        ) {
                            computeBackGesturePlan(topNavigatable, revealedNavigatable, width, height)
                        }
                        ContentScrubPreview(
                            revealedEntry = activeKind.revealedEntry,
                            topDriver = TransitionProgressDriver.External(
                                progress = { interactiveController.progress },
                                resolved = plan.top.resolved,
                                reversedProgress = plan.top.reversedProgress
                            ),
                            revealedDriver = TransitionProgressDriver.External(
                                progress = { interactiveController.progress },
                                resolved = plan.revealed.resolved,
                                reversedProgress = plan.revealed.reversedProgress
                            )
                        )
                    } else null
                } else null
            }

            is InteractiveTransitionController.ScrubKind.ContentDismiss -> {
                if (activeKind.topEntry.stableKey == currentEntry.stableKey) {
                    val topNavigatable = navModule.resolveNavigatable(activeKind.topEntry)
                    if (topNavigatable != null) {
                        val width = windowInfoForScrub.containerSize.width.toFloat()
                        val height = windowInfoForScrub.containerSize.height.toFloat()
                        val revealedNavigatable = activeKind.revealedEntry
                            ?.let { navModule.resolveNavigatable(it) }
                        val plan = remember(
                            activeKind.topEntry.stableKey,
                            activeKind.revealedEntry?.stableKey,
                            width,
                            height
                        ) {
                            computeDismissGesturePlan(topNavigatable, revealedNavigatable, width, height)
                        }
                        ContentScrubPreview(
                            revealedEntry = activeKind.revealedEntry,
                            topDriver = TransitionProgressDriver.External(
                                progress = { interactiveController.progress },
                                resolved = plan.top.resolved,
                                reversedProgress = plan.top.reversedProgress
                            ),
                            revealedDriver = TransitionProgressDriver.External(
                                progress = { interactiveController.progress },
                                resolved = plan.revealed.resolved,
                                reversedProgress = plan.revealed.reversedProgress
                            )
                        )
                    } else null
                } else null
            }

            else -> null
        }
    } else null

    val currentGraphId = navModule.getGraphId(currentEntry) ?: currentEntry.route
    val layoutGraphs = findLayoutGraphsInHierarchy(currentGraphId, graphDefinitions)
    val prevEntry = animationState.previousEntry
    val prevLayoutGraphs = prevEntry?.let {
        val prevGraphId = navModule.getGraphId(it) ?: it.route
        findLayoutGraphsInHierarchy(prevGraphId, graphDefinitions)
    }
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

    val previewRevealedEntry = scrubPreview?.revealedEntry
    val previewRevealedLayouts = previewRevealedEntry?.let {
        val revealedGraphId = navModule.getGraphId(it) ?: it.route
        findLayoutGraphsInHierarchy(revealedGraphId, graphDefinitions)
    }
    val crossHierarchyPreview = scrubPreview != null &&
        previewRevealedEntry != null &&
        previewRevealedLayouts != null &&
        previewRevealedLayouts.map { it.route } != layoutGraphs.map { it.route }

    Box(modifier = Modifier.fillMaxSize()) {
        if (crossHierarchyPreview && previewRevealedEntry != null && previewRevealedLayouts != null) {
            val previewSharedRoutes = layoutGraphs.map { it.route }.toSet()
                .intersect(previewRevealedLayouts.map { it.route }.toSet())
            val previewShared = layoutGraphs.filter { it.route in previewSharedRoutes }
            val previewTopUnique = layoutGraphs.filter { it.route !in previewSharedRoutes }
            val previewRevealedUnique = previewRevealedLayouts.filter { it.route !in previewSharedRoutes }

            ApplyLayoutsHierarchy(previewShared) {
                Box(modifier = Modifier.fillMaxSize()) {
                    key(previewRevealedEntry.stableKey) {
                        val revealedNavigatable = navModule.resolveNavigatable(previewRevealedEntry)
                        NavigationAnimations.AnimatedEntry(
                            entry = previewRevealedEntry,
                            animationType = NavigationAnimations.AnimationType.SCREEN_EXIT,
                            animationDecision = null,
                            screenWidth = windowInfo.containerSize.width.toFloat(),
                            screenHeight = windowInfo.containerSize.height.toFloat(),
                            zIndex = NavigationZIndex.CONTENT_BACK,
                            onAnimationComplete = null,
                            progressDriver = scrubPreview!!.revealedDriver
                        ) {
                            ApplyLayoutsHierarchy(previewRevealedUnique) {
                                revealedNavigatable?.Content(previewRevealedEntry.params)
                            }
                        }
                    }
                    key(currentEntry.stableKey) {
                        val topNavigatable = navModule.resolveNavigatable(currentEntry)
                        NavigationAnimations.AnimatedEntry(
                            entry = currentEntry,
                            animationType = NavigationAnimations.AnimationType.SCREEN_EXIT,
                            animationDecision = null,
                            screenWidth = windowInfo.containerSize.width.toFloat(),
                            screenHeight = windowInfo.containerSize.height.toFloat(),
                            zIndex = NavigationZIndex.CONTENT_FRONT,
                            onAnimationComplete = null,
                            progressDriver = scrubPreview.topDriver
                        ) {
                            ApplyLayoutsHierarchy(previewTopUnique) {
                                topNavigatable?.Content(currentEntry.params)
                            }
                        }
                    }
                }
            }
        } else if (useInsideStrategy && prevEntry != null) {
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
                        val prevNavigatable = navModule.resolveNavigatable(prevEntry)
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
                                prevNavigatable?.Content(prevEntry.params)
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
                        animationDecision = animationState.animationDecision
                    )
                } else {
                    animationState
                }
                ContentRenderer(innerState, scrubPreview)
            }
            if (useOutsideStrategy && prevEntry != null) {
                key(prevEntry.stableKey) {
                    val prevNavigatable = navModule.resolveNavigatable(prevEntry)
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
                            prevNavigatable?.Content(prevEntry.params)
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
    val navModule = LocalNavigationModule.current
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
            .sortedBy { navModule.resolveNavigatable(it.entry)?.elevation ?: 0f }
            .forEach { modalState ->
                key(modalState.entry.stableKey) {
                    val navigatable = navModule.resolveNavigatable(modalState.entry)
                    NavigationAnimations.AnimatedEntry(
                        entry = modalState.entry,
                        animationType = modalState.animationType,
                        animationDecision = null,
                        screenWidth = screenWidth,
                        screenHeight = screenHeight,
                        zIndex = NavigationZIndex.GLOBAL_OVERLAY_BASE + (navigatable?.elevation ?: 0f),
                        onAnimationComplete = {
                            val completed = modalState.markCompleted()
                            if (completed != null) {
                                activeStates[modalState.entry.stableKey] = completed
                            } else {
                                activeStates.remove(modalState.entry.stableKey)
                            }
                        }
                    ) {
                        navigatable?.Content(modalState.entry.params)
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
    val navModule = LocalNavigationModule.current
    val windowInfo = LocalWindowInfo.current
    val screenWidth = windowInfo.containerSize.width.toFloat()
    val screenHeight = windowInfo.containerSize.height.toFloat()

    entries
        .sortedBy { navModule.resolveNavigatable(it)?.elevation ?: 0f }
        .forEach { entry ->
            val navigatable = navModule.resolveNavigatable(entry)
            key(entry.stableKey) {
                if (navigatable is Modal) {
                    NavigationAnimations.AnimatedEntry(
                        entry = entry,
                        animationType = NavigationAnimations.AnimationType.MODAL_ENTER,
                        animationDecision = null,
                        screenWidth = screenWidth,
                        screenHeight = screenHeight,
                        zIndex = NavigationZIndex.SYSTEM_BASE + navigatable.elevation,
                        onAnimationComplete = null
                    ) {
                        navigatable.Content(entry.params)
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .zIndex(9001f + (navigatable?.elevation ?: 0f))
                    ) {
                        navigatable?.Content(entry.params)
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
private fun ContentRenderer(
    animationState: LayerAnimationState,
    scrubPreview: ContentScrubPreview? = null
) {
    val navModule = LocalNavigationModule.current

    val windowInfo = LocalWindowInfo.current
    val screenWidth = windowInfo.containerSize.width.toFloat()
    val screenHeight = windowInfo.containerSize.height.toFloat()

    val shouldExitBeOnTop = animationState.animationDecision?.let { decision ->
        decision.enterTransition is io.github.syrou.reaktiv.navigation.transition.NavTransition.None &&
                decision.exitTransition !is io.github.syrou.reaktiv.navigation.transition.NavTransition.None
    } ?: false

    val revealedPreviewEntry = scrubPreview?.revealedEntry
    val renderedEntries = if (
        revealedPreviewEntry != null &&
        animationState.aliveEntries.none { it.stableKey == revealedPreviewEntry.stableKey }
    ) {
        animationState.aliveEntries + revealedPreviewEntry
    } else {
        animationState.aliveEntries
    }

    Box(modifier = Modifier.fillMaxSize()) {
        renderedEntries.forEach { entry ->
            val isCurrentScreen = entry.stableKey == animationState.currentEntry.stableKey
            val isPreviousScreen = entry.stableKey == animationState.previousEntry?.stableKey
            val isRevealedPreview = entry.stableKey == revealedPreviewEntry?.stableKey
            val navigatable = navModule.resolveNavigatable(entry)

            key(entry.stableKey) {
                val zIndex = when {
                    isCurrentScreen -> if (shouldExitBeOnTop) NavigationZIndex.CONTENT_BACK else NavigationZIndex.CONTENT_FRONT
                    isRevealedPreview -> NavigationZIndex.CONTENT_BACK
                    isPreviousScreen -> if (shouldExitBeOnTop) NavigationZIndex.CONTENT_FRONT else NavigationZIndex.CONTENT_BACK
                    else -> 1f
                }

                val progressDriver = when {
                    scrubPreview != null && isCurrentScreen -> scrubPreview.topDriver
                    scrubPreview != null && isRevealedPreview -> scrubPreview.revealedDriver
                    else -> TransitionProgressDriver.Timed
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
                    onAnimationComplete = null,
                    progressDriver = progressDriver
                ) {
                    navigatable?.Content(entry.params)
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
