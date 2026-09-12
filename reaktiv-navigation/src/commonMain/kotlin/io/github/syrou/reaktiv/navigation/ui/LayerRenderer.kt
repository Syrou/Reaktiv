package io.github.syrou.reaktiv.navigation.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import io.github.syrou.reaktiv.navigation.definition.LoadingModal
import io.github.syrou.reaktiv.navigation.param.Params
import io.github.syrou.reaktiv.navigation.layer.RenderLayer
import io.github.syrou.reaktiv.navigation.model.NavigationEntry
import io.github.syrou.reaktiv.navigation.transition.NavTransition
import io.github.syrou.reaktiv.navigation.transition.computeBackGesturePlan
import io.github.syrou.reaktiv.navigation.transition.computeDismissGesturePlan
import io.github.syrou.reaktiv.navigation.util.AnimationDecision
import io.github.syrou.reaktiv.navigation.util.canArmSwipeDismiss
import io.github.syrou.reaktiv.navigation.util.findLayoutGraphsInHierarchy
import io.github.syrou.reaktiv.navigation.util.dismissIndicatorAnchor
import io.github.syrou.reaktiv.navigation.util.revealedEntryForDismiss
import io.github.syrou.reaktiv.navigation.transition.TransitionSpec
import io.github.syrou.reaktiv.navigation.util.presentationSourceFor

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
 * Each rendered screen is nested under its own graph layouts by [buildLayoutTree], and layouts
 * two screens have in common are composed once around both of them. A screen's position in the
 * composition therefore depends only on that screen, which is what lets it keep its remembered
 * state, its DisposableEffect and its running animations for as long as it is on the back stack.
 *
 * Which element plays a transition follows from the same tree. A screen arriving under chrome that
 * is already on screen animates alone, inside chrome that stays put, while a screen arriving with
 * chrome of its own animates together with it, because the outermost layout enclosing only that
 * screen is the one carrying its transition.
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

    val currentLayoutRoutes = currentLayouts.map { it.route }
    val placement = decideTransitionPlacement(
        currentLayoutRoutes = currentLayoutRoutes,
        previousLayoutRoutes = prevLayouts?.map { it.route },
        decision = animationState.animationDecision
    )

    val slots = buildList {
        if (revealedEntry != null) {
            add(
                ContentSlot(
                    entry = revealedEntry,
                    layouts = revealedLayouts.orEmpty(),
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
                    layouts = prevLayouts.orEmpty(),
                    zIndex = placement.previousZIndex,
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
                layouts = currentLayouts,
                hostedModals = contentModals,
                zIndex = placement.currentZIndex,
                isEntering = true,
                animationDecision = animationState.animationDecision
                    .takeIf { placement.currentPlaysTransition },
                progressDriver = scrubPreview?.topDriver ?: TransitionProgressDriver.Timed,
                blockInput = false,
                clearSemantics = false
            )
        )
    }

    val windowInfo = LocalWindowInfo.current
    val screenWidth = windowInfo.containerSize.width.toFloat()
    val screenHeight = windowInfo.containerSize.height.toFloat()

    val slotLayoutRoutes = slots.map { slot -> slot.layouts.map { it.route } }
    // Where the grab affordance goes follows from the entry and the graph declarations alone.
    // Deriving it from whatever sat beneath instead put the same screen's affordance above a graph
    // layout when the screen was reached from outside that layout and inside the layout when it was
    // reached from a screen standing under it, and moved it from the one to the other while a
    // transition was still running.
    val tree = buildLayoutTree(
        slots.mapIndexed { index, slot ->
            LayoutTreeSlot(
                key = slot.entry.stableKey,
                layoutRoutes = slotLayoutRoutes[index],
                zIndex = slot.zIndex,
                indicatorAnchor = if (interactiveController == null) {
                    null
                } else {
                    dismissIndicatorAnchor(slot.entry, navModule, slotLayoutRoutes[index])
                },
                shielded = slot.entry.stableKey == revealedEntry?.stableKey
            )
        }
    )
    val slotsByKey = slots.associateBy { it.entry.stableKey }
    val graphsByRoute = slots.flatMap { it.layouts }.associateBy { it.route }

    Box(modifier = Modifier.fillMaxSize()) {
        LayoutTreeNodes(
            nodes = tree,
            slotsByKey = slotsByKey,
            graphsByRoute = graphsByRoute,
            screenWidth = screenWidth,
            screenHeight = screenHeight
        )
    }
}

private class ContentSlot(
    val entry: NavigationEntry,
    val layouts: List<NavigationGraph>,
    val hostedModals: List<NavigationEntry> = emptyList(),
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
private fun Modifier.slotTransition(
    slot: ContentSlot,
    screenWidth: Float,
    screenHeight: Float
): Modifier {
    val transition = if (slot.isEntering) {
        slot.animationDecision?.enterTransition ?: NavTransition.None
    } else {
        slot.animationDecision?.exitTransition ?: NavTransition.None
    }
    return animateNavTransition(
        transition = transition,
        isEntering = slot.isEntering,
        animationDecision = slot.animationDecision,
        screenWidth = screenWidth,
        screenHeight = screenHeight,
        entryKey = slot.entry.stableKey,
        onAnimationComplete = null,
        progressDriver = slot.progressDriver
    )
}

/**
 * Renders one level of the layout tree.
 *
 * Every element a slot sits under is rendered unconditionally, so what changes as slots come and
 * go is which element carries the transition and which one reserves the dismiss affordance, never
 * where the screen itself is composed.
 */
@Composable
private fun LayoutTreeNodes(
    nodes: List<LayoutTreeNode>,
    slotsByKey: Map<String, ContentSlot>,
    graphsByRoute: Map<String, NavigationGraph>,
    screenWidth: Float,
    screenHeight: Float
) {
    nodes.forEach { node ->
        key(node.key) {
            when (node) {
                is LayoutTreeBranch -> LayoutBranchHost(
                    branch = node,
                    slotsByKey = slotsByKey,
                    graphsByRoute = graphsByRoute,
                    screenWidth = screenWidth,
                    screenHeight = screenHeight
                )

                is LayoutTreeLeaf -> EntryHost(
                    slot = slotsByKey.getValue(node.slotKey),
                    ownsTransition = node.ownsTransition,
                    ownsIndicator = node.ownsIndicator,
                    screenWidth = screenWidth,
                    screenHeight = screenHeight
                )

                is LayoutTreeShield -> RevealedInputShield()
            }
        }
    }
}

@Composable
private fun LayoutBranchHost(
    branch: LayoutTreeBranch,
    slotsByKey: Map<String, ContentSlot>,
    graphsByRoute: Map<String, NavigationGraph>,
    screenWidth: Float,
    screenHeight: Float
) {
    val owner = branch.ownerSlotKey?.let { slotsByKey[it] }
    val indicatorSlot = branch.indicatorSlotKey?.let { slotsByKey[it] }
    val inheritedEntry = LocalRenderedEntry.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(branch.zIndex)
            .then(
                if (owner != null) {
                    Modifier.slotTransition(owner, screenWidth, screenHeight)
                } else {
                    Modifier
                }
            )
    ) {
        CompositionLocalProvider(LocalRenderedEntry provides (owner?.entry ?: inheritedEntry)) {
            // A slot anchors here when the drag would take this chrome away along with it, so the
            // grab strip sits above the chrome rather than under it, where a drag starting on the
            // header would miss it. The strip is composed whether or not it holds an affordance,
            // because a wrapper that came and went as a sheet arrived over the layout would take
            // the layout and its screens down with it.
            DismissIndicatorSlot(indicatorEntry = indicatorSlot?.entry) {
                GraphLayout(graphsByRoute[branch.route]) {
                    // Everything under one layout occupies the same space and is ordered by zIndex.
                    // Handing the children straight to the layout would let a Column or a Scaffold
                    // stack the screens one after another instead.
                    Box(modifier = Modifier.fillMaxSize()) {
                        LayoutTreeNodes(
                            nodes = branch.children,
                            slotsByKey = slotsByKey,
                            graphsByRoute = graphsByRoute,
                            screenWidth = screenWidth,
                            screenHeight = screenHeight
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GraphLayout(graph: NavigationGraph?, content: @Composable () -> Unit) {
    val layout = graph?.layout
    if (layout != null) {
        layout { content() }
    } else {
        content()
    }
}

@Composable
private fun EntryHost(
    slot: ContentSlot,
    ownsTransition: Boolean,
    ownsIndicator: Boolean,
    screenWidth: Float,
    screenHeight: Float
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(slot.zIndex)
            .then(if (slot.clearSemantics) Modifier.clearAndSetSemantics { } else Modifier)
            .then(
                if (ownsTransition) {
                    Modifier.slotTransition(slot, screenWidth, screenHeight)
                } else {
                    Modifier
                }
            )
            .then(if (slot.blockInput) Modifier.consumeAllPointerInput() else Modifier)
    ) {
        // Painting only when a colour was actually provided. Color.Unspecified is the default of
        // LocalNavigationBackgroundColor and is not a paintable value, and a layout that owns its
        // own surface provides Transparent here so the slot stops covering it.
        val slotBackground = rememberNavigationBackgroundColor()
        Box(modifier = Modifier.fillMaxSize()) {
            HostedEntry(slot.entry) {
                DismissIndicatorSlot(
                    indicatorEntry = slot.entry.takeIf { ownsIndicator },
                    contentBackground = slotBackground
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        NavigatableContent(slot.entry.navigatable, slot.entry.params)
                        ModalStack(slot.hostedModals, NavigationZIndex.CONTENT_MODAL_BASE)
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
                    NavigatableContent(navigatable, modalState.entry.params)
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
                            NavigatableContent(navigatable, entry.params)
                        }
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .zIndex(9001f + navigatable.elevation)
                    ) {
                        HostedEntry(entry) {
                            NavigatableContent(navigatable, entry.params)
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
            NavigatableContent(evaluationOverlay, Params.empty())
        }
    }
}
