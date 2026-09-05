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
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.zIndex
import io.github.syrou.reaktiv.compose.composeState
import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.definition.Modal
import io.github.syrou.reaktiv.navigation.definition.NavigationGraph
import io.github.syrou.reaktiv.navigation.definition.LoadingModal
import io.github.syrou.reaktiv.navigation.param.Params
import io.github.syrou.reaktiv.navigation.layer.RenderLayer
import io.github.syrou.reaktiv.navigation.model.NavigationEntry
import io.github.syrou.reaktiv.navigation.transition.NavTransition
import io.github.syrou.reaktiv.navigation.transition.computeBackGesturePlan
import io.github.syrou.reaktiv.navigation.transition.computeDismissGesturePlan
import io.github.syrou.reaktiv.navigation.util.AnimationDecision
import io.github.syrou.reaktiv.navigation.util.canArmInteractiveBackGesture
import io.github.syrou.reaktiv.navigation.util.contentEntryBeneath
import io.github.syrou.reaktiv.navigation.util.presentsDismissIndicator
import io.github.syrou.reaktiv.navigation.util.canArmSwipeDismiss
import io.github.syrou.reaktiv.navigation.util.findLayoutGraphsInHierarchy
import io.github.syrou.reaktiv.navigation.util.dismissableBoundary
import io.github.syrou.reaktiv.navigation.util.revealedEntryForDismiss
import io.github.syrou.reaktiv.navigation.transition.TransitionSpec
import io.github.syrou.reaktiv.navigation.util.presentationSourceFor
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
    const val CONTENT_MODAL_BASE = 10f
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
    graphDefinitions: Map<String, NavigationGraph>,
    evaluationOverlay: LoadingModal? = null
) {
    when (layerType) {
        RenderLayer.SYSTEM ->
            if (entries.isNotEmpty() || evaluationOverlay != null) {
                SystemLayerRenderer(entries, evaluationOverlay)
            }

        RenderLayer.CONTENT ->
            if (entries.isNotEmpty()) ContentLayerRenderer(entries, graphDefinitions)

        RenderLayer.GLOBAL_OVERLAY -> OverlayLayerRenderer(entries)
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
 * OUTSIDE: When no shared chrome exists (e.g. login -> projects). The exiting screen is lifted
 * outside the incoming layout hierarchy entirely, placed at the top level at zIndex=100.
 */
@Composable
private fun ContentLayerRenderer(
    entries: List<NavigationEntry>,
    graphDefinitions: Map<String, NavigationGraph>
) {
    val navModule = LocalNavigationModule.current
    val contentModals = entries.filter { it.navigatable is Modal }
    val screenEntries = entries.filter { it.navigatable !is Modal }
    if (screenEntries.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize()) {
            ModalStack(contentModals, NavigationZIndex.CONTENT_MODAL_BASE)
        }
        return
    }
    val currentEntry = screenEntries.last()

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
        val topSource = presentationSourceFor(
            from = activeKind.revealedEntry,
            to = activeKind.topEntry,
            navigatable = activeKind.topEntry.navigatable,
            navModule = navModule
        )
        val revealedSource = presentationSourceFor(
            from = activeKind.topEntry,
            to = activeKind.revealedEntry,
            navigatable = activeKind.revealedEntry.navigatable,
            navModule = navModule
        )
        val width = windowInfoForScrub.containerSize.width.toFloat()
        val height = windowInfoForScrub.containerSize.height.toFloat()
        val plan = remember(
            activeKind.topEntry.stableKey,
            activeKind.revealedEntry.stableKey,
            width,
            height
        ) {
            computeBackGesturePlan(topSource, revealedSource, width, height)
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
            currentEntry to revealedEntryForDismiss(navigationState, navModule)
        }

        else -> null
    }
    val dismissTopEntry = dismissPair?.first
    val dismissPreview: ContentScrubPreview? = if (
        interactiveController != null &&
        dismissPair != null &&
        dismissTopEntry != null
    ) {
        val dismissRevealed = dismissPair.second
        val dismissTopSource: TransitionSpec = dismissRevealed?.let {
            presentationSourceFor(
                from = it,
                to = dismissTopEntry,
                navigatable = dismissTopEntry.navigatable,
                navModule = navModule
            )
        } ?: dismissTopEntry.navigatable
        val dismissRevealedSource = dismissRevealed?.let {
            presentationSourceFor(
                from = dismissTopEntry,
                to = it,
                navigatable = it.navigatable,
                navModule = navModule
            )
        }
        val width = windowInfoForScrub.containerSize.width.toFloat()
        val height = windowInfoForScrub.containerSize.height.toFloat()
        val plan = remember(
            dismissPair.first.stableKey,
            dismissRevealed?.stableKey,
            width,
            height
        ) {
            computeDismissGesturePlan(dismissTopSource, dismissRevealedSource, width, height)
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
        navigationState.currentEntry.stableKey == currentEntry.stableKey
    ) {
        when {
            canArmInteractiveBackGesture(navigationState, navModule) ->
                revealedEntryForBack(navigationState)
            presentsDismissIndicator(currentEntry, navModule) &&
                dismissableBoundary(currentEntry, navModule) == null ->
                contentEntryBeneath(navigationState)
            else -> null
        }
    } else null
    val restingBackLayouts = restingBackRevealed?.let {
        val backGraphId = navModule.getGraphId(it) ?: it.route
        findLayoutGraphsInHierarchy(backGraphId, graphDefinitions)
    }

    val sharing = decideLayoutSharing(
        currentLayoutRoutes = currentLayouts.map { it.route },
        previousLayoutRoutes = prevLayouts?.map { it.route },
        revealedLayoutRoutes = revealedLayouts?.map { it.route },
        restingBackLayoutRoutes = restingBackLayouts?.map { it.route },
        shouldAnimateExit = animationState.animationDecision?.shouldAnimateExit ?: false
    )
    val sharedRoutes = sharing.sharedRoutes
    val liftExiting = sharing.liftExiting
    val sharedLayouts = currentLayouts.filter { it.route in sharedRoutes }
    val currentUnique = currentLayouts.filter { it.route !in sharedRoutes }
    val prevUnique = prevLayouts.orEmpty().filter { it.route in sharing.exitingUniqueRoutes }
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

    val graphDismissBoundary = dismissableBoundary(navigationState.currentEntry, navModule)
    val indicatorOwnsSharedChrome = graphDismissBoundary != null &&
        graphDismissBoundary in sharedRoutes

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
                hostedModals = contentModals,
                indicatorHoisted = indicatorOwnsSharedChrome,
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

    // A graph presented as a draggable surface owns its chrome, so the dismiss affordance belongs
    // above the shared layouts rather than inside them. Once past the first screen the graph's
    // layout is shared between steps and renders outside the slots, and an indicator nested under
    // it would sit below the chrome it is supposed to drag.
    Box(modifier = Modifier.fillMaxSize()) {
        OptionalDismissIndicator(
            entry = navigationState.currentEntry,
            enabled = indicatorOwnsSharedChrome
        ) {
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
}

/**
 * Wraps [content] in the dismiss affordance only when this level owns it.
 *
 * The affordance is rendered once, at whichever level represents the surface being dragged: around
 * the shared chrome for a graph presented as a sheet, or around the slot for a single screen.
 */
@Composable
private fun OptionalDismissIndicator(
    entry: NavigationEntry,
    enabled: Boolean,
    background: Color = Color.Unspecified,
    content: @Composable () -> Unit
) {
    if (enabled) {
        DismissIndicatorSlot(entry, contentBackground = background) { content() }
    } else {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (background.isSpecified && background != Color.Transparent) {
                        Modifier.background(background)
                    } else {
                        Modifier
                    }
                )
        ) {
            content()
        }
    }
}

private class ContentSlot(
    val entry: NavigationEntry,
    val uniqueLayouts: List<NavigationGraph>,
    val hostedModals: List<NavigationEntry> = emptyList(),
    /** True when the shared chrome level renders the dismiss affordance instead of this slot. */
    val indicatorHoisted: Boolean = false,
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
        // Painting only when a colour was actually provided. Color.Unspecified is the default of
        // LocalNavigationBackgroundColor and is not a paintable value, and a layout that owns its
        // own surface provides Transparent here so the slot stops covering it.
        val slotBackground = rememberNavigationBackgroundColor()
        Box(modifier = Modifier.fillMaxSize()) {
            HostedEntry(slot.entry) {
                // The dismiss affordance belongs to the surface being dismissed, which is the
                // whole slot including the graph layouts that arrived with it, not just the screen
                // inside them. Nesting it under the layouts would put the grab pill below the
                // graph's own chrome and measure the dismiss zone from the screen's bounds, so a
                // drag starting on the header would miss it.
                OptionalDismissIndicator(
                    entry = slot.entry,
                    enabled = !slot.indicatorHoisted,
                    background = slotBackground
                ) {
                    ApplyLayoutsHierarchy(slot.uniqueLayouts) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            slot.entry.navigatable.Content(slot.entry.params)
                            ModalStack(slot.hostedModals, NavigationZIndex.CONTENT_MODAL_BASE)
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
    Box(modifier = Modifier.fillMaxSize()) {
        ModalStack(entries, NavigationZIndex.GLOBAL_OVERLAY_BASE)
    }
}

@Composable
private fun ModalStack(
    entries: List<NavigationEntry>,
    zIndexBase: Float
) {
    val stack = rememberModalStackStates(entries)

    val windowInfo = LocalWindowInfo.current
    val screenWidth = windowInfo.containerSize.width.toFloat()
    val screenHeight = windowInfo.containerSize.height.toFloat()

    stack.states.forEach { modalState ->
        key(modalState.entry.stableKey) {
            val navigatable = modalState.entry.navigatable
            NavigationAnimations.AnimatedEntry(
                entry = modalState.entry,
                animationType = modalState.animationType,
                screenWidth = screenWidth,
                screenHeight = screenHeight,
                zIndex = zIndexBase + navigatable.elevation,
                onAnimationComplete = { stack.completed(modalState.entry.stableKey) }
            ) {
                HostedEntry(modalState.entry) {
                    navigatable.Content(modalState.entry.params)
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
 *
 * [evaluationOverlay] is the loading modal shown while navigation is being evaluated. It has no
 * backstack entry of its own, but it belongs to this layer and must be ordered here rather than
 * beside it: zIndex only orders siblings, so an overlay drawn outside this renderer covers every
 * system entry regardless of elevation, hiding alerts that are meant to sit above everything.
 */
@Composable
private fun SystemLayerRenderer(
    entries: List<NavigationEntry>,
    evaluationOverlay: LoadingModal? = null
) {
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

    if (evaluationOverlay != null) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .zIndex(NavigationZIndex.SYSTEM_BASE + evaluationOverlay.elevation)
        ) {
            evaluationOverlay.Content(Params.empty())
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
