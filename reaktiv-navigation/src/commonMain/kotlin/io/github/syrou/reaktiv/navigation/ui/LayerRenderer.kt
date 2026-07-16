package io.github.syrou.reaktiv.navigation.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.zIndex
import io.github.syrou.reaktiv.compose.composeState
import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.definition.Modal
import io.github.syrou.reaktiv.navigation.definition.NavigationGraph
import io.github.syrou.reaktiv.navigation.layer.RenderLayer
import io.github.syrou.reaktiv.navigation.model.NavigationEntry
import io.github.syrou.reaktiv.navigation.transition.NavTransition
import io.github.syrou.reaktiv.navigation.transition.computeBackGesturePlan
import io.github.syrou.reaktiv.navigation.transition.computeDismissGesturePlan
import io.github.syrou.reaktiv.navigation.util.AnimationDecision
import io.github.syrou.reaktiv.navigation.util.canArmInteractiveBackGesture
import io.github.syrou.reaktiv.navigation.util.canArmSwipeDismiss
import io.github.syrou.reaktiv.navigation.util.findLayoutGraphsInHierarchy
import io.github.syrou.reaktiv.navigation.util.revealedEntryForBack

internal class ContentScrubPreview(
    val revealedEntry: NavigationEntry?,
    val topDriver: TransitionProgressDriver.External,
    val revealedDriver: TransitionProgressDriver.External
)

internal object NavigationZIndex {
    const val CONTENT_BACK = 2f
    const val CONTENT_REVEALED_SHIELD = 2.5f
    const val CONTENT_FRONT = 3f
    const val CONTENT_LIFTED_EXIT = 100f
    const val GLOBAL_OVERLAY_BASE = 2000f
    const val SYSTEM_BASE = 9001f
}

@Composable
private fun HostedEntry(entry: NavigationEntry, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalRenderedEntry provides entry) {
        content()
    }
}

@Composable
private fun RevealedInputShield() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(NavigationZIndex.CONTENT_REVEALED_SHIELD)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent()
                    }
                }
            }
    )
}

/**
 * Unified layer renderer that handles all navigation layer types consistently
 */
@Composable
public fun UnifiedLayerRenderer(
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
    val navigationState by composeState<NavigationState>()

    val backPreview: ContentScrubPreview? = if (
        interactiveController != null &&
        interactiveController.phase != InteractiveTransitionController.Phase.Idle &&
        activeKind is InteractiveTransitionController.ScrubKind.ContentBack &&
        activeKind.topEntry.stableKey == currentEntry.stableKey
    ) {
        val topNavigatable = activeKind.topEntry.navigatable
        val revealedNavigatable = activeKind.revealedEntry.navigatable
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

    val dismissPair: Pair<NavigationEntry, NavigationEntry?>? = when {
        interactiveController == null -> null
        interactiveController.phase != InteractiveTransitionController.Phase.Idle -> {
            if (
                activeKind is InteractiveTransitionController.ScrubKind.ContentDismiss &&
                activeKind.topEntry.stableKey == currentEntry.stableKey
            ) {
                activeKind.topEntry to activeKind.revealedEntry
            } else null
        }

        animationState.previousEntry == null &&
            navigationState.currentEntry.stableKey == currentEntry.stableKey &&
            currentEntry.navigatable !is Modal &&
            canArmSwipeDismiss(navigationState, navModule) -> {
            currentEntry to revealedEntryForBack(navigationState)
        }

        else -> null
    }
    val dismissTopNavigatable = dismissPair?.first?.navigatable
    val dismissPreview: ContentScrubPreview? = if (
        interactiveController != null &&
        dismissPair != null &&
        dismissTopNavigatable != null
    ) {
        val dismissRevealed = dismissPair.second
        val width = windowInfoForScrub.containerSize.width.toFloat()
        val height = windowInfoForScrub.containerSize.height.toFloat()
        val plan = remember(
            dismissPair.first.stableKey,
            dismissRevealed?.stableKey,
            width,
            height
        ) {
            computeDismissGesturePlan(dismissTopNavigatable, dismissRevealed?.navigatable, width, height)
        }
        ContentScrubPreview(
            revealedEntry = dismissRevealed,
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

    val scrubPreview: ContentScrubPreview? = backPreview ?: dismissPreview
    val revealedAtRest = interactiveController?.phase == InteractiveTransitionController.Phase.Idle

    val currentGraphId = navModule.getGraphId(currentEntry) ?: currentEntry.route
    val currentLayouts = findLayoutGraphsInHierarchy(currentGraphId, graphDefinitions)
    val prevEntry = animationState.previousEntry?.takeIf { it.stableKey != currentEntry.stableKey }
    val prevLayouts = prevEntry?.let {
        val prevGraphId = navModule.getGraphId(it) ?: it.route
        findLayoutGraphsInHierarchy(prevGraphId, graphDefinitions)
    }
    val revealedEntry = scrubPreview?.revealedEntry?.takeIf { revealed ->
        revealed.stableKey != currentEntry.stableKey && revealed.stableKey != prevEntry?.stableKey
    }
    val revealedLayouts = revealedEntry?.let {
        val revealedGraphId = navModule.getGraphId(it) ?: it.route
        findLayoutGraphsInHierarchy(revealedGraphId, graphDefinitions)
    }

    val restingBackRevealed = if (
        revealedEntry == null &&
        navigationState.currentEntry.stableKey == currentEntry.stableKey &&
        canArmInteractiveBackGesture(navigationState, navModule)
    ) {
        revealedEntryForBack(navigationState)
    } else null
    val restingBackLayouts = restingBackRevealed?.let {
        val backGraphId = navModule.getGraphId(it) ?: it.route
        findLayoutGraphsInHierarchy(backGraphId, graphDefinitions)
    }

    val layoutChanged = prevLayouts != null &&
        prevLayouts.map { it.route } != currentLayouts.map { it.route }
    val liftExiting = layoutChanged && (animationState.animationDecision?.shouldAnimateExit ?: false)

    var sharedRoutes = currentLayouts.map { it.route }.toSet()
    if (liftExiting) {
        sharedRoutes = sharedRoutes.intersect(prevLayouts.orEmpty().map { it.route }.toSet())
    }
    if (revealedLayouts != null) {
        sharedRoutes = sharedRoutes.intersect(revealedLayouts.map { it.route }.toSet())
    }
    if (restingBackLayouts != null) {
        sharedRoutes = sharedRoutes.intersect(restingBackLayouts.map { it.route }.toSet())
    }
    val sharedLayouts = currentLayouts.filter { it.route in sharedRoutes }
    val currentUnique = currentLayouts.filter { it.route !in sharedRoutes }
    val prevUnique = if (liftExiting) {
        prevLayouts.orEmpty().filter { it.route !in sharedRoutes }
    } else {
        emptyList()
    }
    val revealedUnique = revealedLayouts.orEmpty().filter { it.route !in sharedRoutes }

    val shouldExitBeOnTop = !liftExiting && (animationState.animationDecision?.let { decision ->
        decision.enterTransition is NavTransition.None &&
            decision.exitTransition !is NavTransition.None
    } ?: false)
    val currentDecision = if (liftExiting && sharedLayouts.isNotEmpty()) {
        null
    } else {
        animationState.animationDecision
    }
    val currentZ = if (shouldExitBeOnTop) NavigationZIndex.CONTENT_BACK else NavigationZIndex.CONTENT_FRONT
    val prevZ = when {
        liftExiting -> NavigationZIndex.CONTENT_LIFTED_EXIT
        shouldExitBeOnTop -> NavigationZIndex.CONTENT_FRONT
        else -> NavigationZIndex.CONTENT_BACK
    }

    val slots = buildList {
        if (revealedEntry != null) {
            add(
                ContentSlot(
                    entry = revealedEntry,
                    uniqueLayouts = revealedUnique,
                    zIndex = NavigationZIndex.CONTENT_BACK,
                    isEntering = false,
                    animationDecision = null,
                    progressDriver = scrubPreview.revealedDriver,
                    blockInput = false,
                    clearSemantics = revealedAtRest
                )
            )
        }
        if (prevEntry != null) {
            add(
                ContentSlot(
                    entry = prevEntry,
                    uniqueLayouts = prevUnique,
                    zIndex = prevZ,
                    isEntering = false,
                    animationDecision = animationState.animationDecision,
                    progressDriver = TransitionProgressDriver.Timed,
                    blockInput = true,
                    clearSemantics = false
                )
            )
        }
        add(
            ContentSlot(
                entry = currentEntry,
                uniqueLayouts = currentUnique,
                zIndex = currentZ,
                isEntering = true,
                animationDecision = currentDecision,
                progressDriver = scrubPreview?.topDriver ?: TransitionProgressDriver.Timed,
                blockInput = false,
                clearSemantics = false
            )
        )
    }

    val windowInfo = LocalWindowInfo.current
    val screenWidth = windowInfo.containerSize.width.toFloat()
    val screenHeight = windowInfo.containerSize.height.toFloat()

    Box(modifier = Modifier.fillMaxSize()) {
        ApplyLayoutsHierarchy(sharedLayouts) {
            Box(modifier = Modifier.fillMaxSize()) {
                slots.forEach { slot ->
                    key(slot.entry.stableKey) {
                        EntryHost(slot, screenWidth, screenHeight)
                    }
                }
                if (revealedEntry != null) {
                    RevealedInputShield()
                }
            }
        }
    }
}

private class ContentSlot(
    val entry: NavigationEntry,
    val uniqueLayouts: List<NavigationGraph>,
    val zIndex: Float,
    val isEntering: Boolean,
    val animationDecision: AnimationDecision?,
    val progressDriver: TransitionProgressDriver,
    val blockInput: Boolean,
    val clearSemantics: Boolean
)

private fun Modifier.consumeAllPointerInput(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            awaitPointerEvent().changes.forEach { it.consume() }
        }
    }
}

@Composable
private fun EntryHost(slot: ContentSlot, screenWidth: Float, screenHeight: Float) {
    val transition = if (slot.isEntering) {
        slot.animationDecision?.enterTransition ?: NavTransition.None
    } else {
        slot.animationDecision?.exitTransition ?: NavTransition.None
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(slot.zIndex)
            .then(if (slot.clearSemantics) Modifier.clearAndSetSemantics { } else Modifier)
            .animateNavTransition(
                transition = transition,
                isEntering = slot.isEntering,
                animationDecision = slot.animationDecision,
                screenWidth = screenWidth,
                screenHeight = screenHeight,
                entryKey = slot.entry.stableKey,
                onAnimationComplete = null,
                progressDriver = slot.progressDriver
            )
            .then(if (slot.blockInput) Modifier.consumeAllPointerInput() else Modifier)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(rememberNavigationBackgroundColor())
        ) {
            HostedEntry(slot.entry) {
                ApplyLayoutsHierarchy(slot.uniqueLayouts) {
                    DismissIndicatorSlot(slot.entry) {
                        slot.entry.navigatable.Content(slot.entry.params)
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
            .sortedBy { it.entry.navigatable.elevation }
            .forEach { modalState ->
                key(modalState.entry.stableKey) {
                    val navigatable = modalState.entry.navigatable
                    NavigationAnimations.AnimatedEntry(
                        entry = modalState.entry,
                        animationType = modalState.animationType,
                        screenWidth = screenWidth,
                        screenHeight = screenHeight,
                        zIndex = NavigationZIndex.GLOBAL_OVERLAY_BASE + navigatable.elevation,
                        onAnimationComplete = {
                            val completed = modalState.markCompleted()
                            if (completed != null) {
                                activeStates[modalState.entry.stableKey] = completed
                            } else {
                                activeStates.remove(modalState.entry.stableKey)
                            }
                        }
                    ) {
                        HostedEntry(modalState.entry) {
                            navigatable.Content(modalState.entry.params)
                        }
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
        .sortedBy { it.navigatable.elevation }
        .forEach { entry ->
            val navigatable = entry.navigatable
            key(entry.stableKey) {
                if (navigatable is Modal) {
                    NavigationAnimations.AnimatedEntry(
                        entry = entry,
                        animationType = NavigationAnimations.AnimationType.MODAL_ENTER,
                        screenWidth = screenWidth,
                        screenHeight = screenHeight,
                        zIndex = NavigationZIndex.SYSTEM_BASE + navigatable.elevation,
                        onAnimationComplete = null
                    ) {
                        HostedEntry(entry) {
                            navigatable.Content(entry.params)
                        }
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .zIndex(9001f + navigatable.elevation)
                    ) {
                        HostedEntry(entry) {
                            navigatable.Content(entry.params)
                        }
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
