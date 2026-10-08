package io.github.syrou.reaktiv.navigation.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.zIndex
import io.github.syrou.reaktiv.compose.composeState
import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.definition.Modal
import io.github.syrou.reaktiv.navigation.definition.NavigationGraph
import io.github.syrou.reaktiv.navigation.definition.LoadingModal
import io.github.syrou.reaktiv.navigation.definition.PaneBlock
import io.github.syrou.reaktiv.navigation.definition.PaneSlot
import io.github.syrou.reaktiv.navigation.definition.WindowWidthClass
import io.github.syrou.reaktiv.navigation.param.Params
import io.github.syrou.reaktiv.navigation.layer.RenderLayer
import io.github.syrou.reaktiv.navigation.model.NavigationEntry
import io.github.syrou.reaktiv.navigation.transition.BackGesturePlan
import io.github.syrou.reaktiv.navigation.transition.NavTransition
import io.github.syrou.reaktiv.navigation.transition.computeBackGesturePlan
import io.github.syrou.reaktiv.navigation.transition.computeDismissGesturePlan
import io.github.syrou.reaktiv.navigation.util.GraphIndex
import io.github.syrou.reaktiv.navigation.util.PaneMath
import io.github.syrou.reaktiv.navigation.util.PaneSurface
import io.github.syrou.reaktiv.navigation.util.AnimationDecision
import io.github.syrou.reaktiv.navigation.util.canArmSwipeDismiss
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
    val graphIndex = remember(graphDefinitions) { GraphIndex.of(graphDefinitions) }
    val navigationState by composeState<NavigationState>()
    val widthClass = navigationState.windowWidthClass
    val paneSurface = navigationState.paneGraph?.let { graphId ->
        PaneMath.activeBlock(graphId, graphDefinitions, widthClass)?.let { block ->
            PaneSurface(graphId, block, navigationState.paneColumns)
        }
    }
    val contentModals = entries.filter { it.navigatable is Modal && paneSurface?.places(it) != true }
    val screenEntries = entries.filter { it.navigatable !is Modal || paneSurface?.places(it) == true }
    if (screenEntries.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize()) {
            ModalStack(contentModals, NavigationZIndex.CONTENT_MODAL_BASE)
        }
        return
    }
    val currentEntry = screenEntries.last()
    val paneMemory = remember { PaneSurfaceMemory() }
    val previousSurface = paneMemory.advance(currentEntry.stableKey, paneSurface)

    val animationState = rememberLayerAnimationState(currentEntry)

    val interactiveController = LocalInteractiveTransitionController.current
    val activeKind = interactiveController?.scrubKind
    val windowInfoForScrub = LocalWindowInfo.current

    fun previewOf(revealed: NavigationEntry?, plan: BackGesturePlan): ContentScrubPreview? =
        interactiveController?.let { controller ->
            ContentScrubPreview(
                revealedEntry = revealed,
                topDriver = TransitionProgressDriver.External(
                    progress = { controller.progress },
                    resolved = plan.top.resolved,
                    reversedProgress = plan.top.reversedProgress
                ),
                revealedDriver = TransitionProgressDriver.External(
                    progress = { controller.progress },
                    resolved = plan.revealed.resolved,
                    reversedProgress = plan.revealed.reversedProgress
                )
            )
        }

    val backPreview: ContentScrubPreview? = if (
        interactiveController != null &&
        interactiveController.phase != InteractiveTransitionController.Phase.Idle &&
        activeKind is InteractiveTransitionController.ScrubKind.ContentBack &&
        activeKind.top.stableKey == currentEntry.stableKey
    ) {
        val topSource = presentationSourceFor(
            from = activeKind.revealed,
            to = activeKind.top,
            navigatable = activeKind.top.navigatable,
            navModule = navModule
        )
        val revealedSource = presentationSourceFor(
            from = activeKind.top,
            to = activeKind.revealed,
            navigatable = activeKind.revealed.navigatable,
            navModule = navModule
        )
        val width = windowInfoForScrub.containerSize.width.toFloat()
        val height = windowInfoForScrub.containerSize.height.toFloat()
        val plan = remember(
            activeKind.top.stableKey,
            activeKind.revealed.stableKey,
            width,
            height
        ) {
            computeBackGesturePlan(topSource, revealedSource, width, height)
        }
        previewOf(activeKind.revealed, plan)
    } else null

    val dismissPair: Pair<NavigationEntry, NavigationEntry?>? = when {
        interactiveController == null -> null
        interactiveController.phase != InteractiveTransitionController.Phase.Idle -> {
            if (
                activeKind is InteractiveTransitionController.ScrubKind.ContentDismiss &&
                activeKind.top.stableKey == currentEntry.stableKey
            ) {
                activeKind.top to activeKind.revealed
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
        previewOf(dismissRevealed, plan)
    } else null

    val scrubPreview: ContentScrubPreview? = backPreview ?: dismissPreview
    val revealedAtRest = interactiveController?.phase == InteractiveTransitionController.Phase.Idle

    fun levelsFor(entry: NavigationEntry): List<String> {
        if (navModule.getGraphId(entry) == null) return emptyList()
        val paneGraph = PaneMath.graphOf(entry, graphDefinitions)
            ?.takeIf { PaneMath.activeBlock(it, graphDefinitions, widthClass) != null }
        val column = paneGraph?.let { PaneMath.columnOf(entry, graphDefinitions, widthClass) }
        return graphIndex.layoutChain(entry.graphId).flatMap { graph ->
            when {
                graph.route == paneGraph -> listOfNotNull(graph.route, column?.let { paneColumnLevel(graph.route, it) })
                graph.layout != null -> listOf(graph.route)
                else -> emptyList()
            }
        }
    }

    val currentLevels = levelsFor(currentEntry)
    val prevEntry = animationState.previousEntry?.takeIf {
        it.stableKey != currentEntry.stableKey && paneSurface?.places(it) != true
    }
    val prevLevels = prevEntry?.let(::levelsFor)
    val revealedEntry = scrubPreview?.revealedEntry?.takeIf { revealed ->
        revealed.stableKey != currentEntry.stableKey &&
            revealed.stableKey != prevEntry?.stableKey &&
            paneSurface?.places(revealed) != true
    }
    val revealedLevels = revealedEntry?.let(::levelsFor)

    val placement = decideTransitionPlacement(
        currentLayoutRoutes = currentLevels,
        previousLayoutRoutes = prevLevels,
        decision = animationState.animationDecision
    )

    val restingEntries = paneSurface?.entries.orEmpty().filter { it.stableKey != currentEntry.stableKey }
    val shownKeys = buildSet {
        add(currentEntry.stableKey)
        prevEntry?.let { add(it.stableKey) }
        revealedEntry?.let { add(it.stableKey) }
        restingEntries.forEach { add(it.stableKey) }
    }
    val leavingFollowers = if (prevEntry == null) {
        emptyList()
    } else {
        previousSurface?.entries.orEmpty().filter { it.stableKey !in shownKeys }
    }
    val revealedSurface = revealedEntry?.let { revealed ->
        val content = navigationState.orderedBackStack.filter { it.navigatable.renderLayer != RenderLayer.SYSTEM }
        val end = content.indexOfLast { it.stableKey == revealed.stableKey }
        if (end < 0) null else PaneMath.surface(content.take(end + 1), widthClass, graphDefinitions)
    }
    val arrivingFollowers = revealedSurface?.entries.orEmpty().filter { arriving ->
        arriving.stableKey !in shownKeys && leavingFollowers.none { it.stableKey == arriving.stableKey }
    }

    val slots = buildList {
        restingEntries.forEach { entry ->
            add(
                ContentSlot(
                    entry = entry,
                    levels = levelsFor(entry),
                    zIndex = NavigationZIndex.CONTENT_BACK,
                    isEntering = false,
                    animationDecision = null,
                    progressDriver = TransitionProgressDriver.Timed,
                    blockInput = false,
                    clearSemantics = false,
                    resting = true
                )
            )
        }
        arrivingFollowers.forEach { entry ->
            add(
                ContentSlot(
                    entry = entry,
                    levels = levelsFor(entry),
                    zIndex = NavigationZIndex.CONTENT_BACK,
                    isEntering = false,
                    animationDecision = null,
                    progressDriver = TransitionProgressDriver.Timed,
                    blockInput = false,
                    clearSemantics = revealedAtRest,
                    resting = true,
                    follows = true,
                    shielded = true
                )
            )
        }
        if (revealedEntry != null) {
            add(
                ContentSlot(
                    entry = revealedEntry,
                    levels = revealedLevels.orEmpty(),
                    zIndex = NavigationZIndex.CONTENT_BACK,
                    isEntering = false,
                    animationDecision = null,
                    progressDriver = scrubPreview.revealedDriver,
                    blockInput = false,
                    clearSemantics = revealedAtRest,
                    shielded = true
                )
            )
        }
        leavingFollowers.forEach { entry ->
            add(
                ContentSlot(
                    entry = entry,
                    levels = levelsFor(entry),
                    zIndex = placement.previousZIndex,
                    isEntering = false,
                    animationDecision = null,
                    progressDriver = TransitionProgressDriver.Timed,
                    blockInput = true,
                    clearSemantics = false,
                    resting = true,
                    follows = true
                )
            )
        }
        if (prevEntry != null) {
            add(
                ContentSlot(
                    entry = prevEntry,
                    levels = prevLevels.orEmpty(),
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
                levels = currentLevels,
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

    // Where the grab affordance goes follows from the entry and the graph declarations alone.
    // Deriving it from whatever sat beneath instead put the same screen's affordance above a graph
    // layout when the screen was reached from outside that layout and inside the layout when it was
    // reached from a screen standing under it, and moved it from the one to the other while a
    // transition was still running.
    val tree = buildLayoutTree(
        slots.map { slot ->
            LayoutTreeSlot(
                key = slot.entry.stableKey,
                layoutRoutes = slot.levels,
                zIndex = slot.zIndex,
                indicatorAnchor = if (interactiveController == null || slot.resting) {
                    null
                } else {
                    dismissIndicatorAnchor(slot.entry, navModule, slot.levels)
                },
                shielded = slot.shielded,
                follows = slot.follows
            )
        }
    )
    val slotsByKey = slots.associateBy { it.entry.stableKey }
    val panes = PaneRendering(graphDefinitions, widthClass, paneSurface)

    Box(modifier = Modifier.fillMaxSize()) {
        LayoutTreeNodes(
            nodes = tree,
            slotsByKey = slotsByKey,
            panes = panes,
            screenWidth = screenWidth,
            screenHeight = screenHeight
        )
    }
}

private class PaneSurfaceMemory {
    private var currentKey: String? = null
    private var surface: PaneSurface? = null
    private var previous: PaneSurface? = null

    fun advance(key: String, current: PaneSurface?): PaneSurface? {
        if (currentKey != key) {
            previous = surface
            currentKey = key
        }
        surface = current
        return previous
    }
}

private class PaneRendering(
    val graphs: Map<String, NavigationGraph>,
    val widthClass: WindowWidthClass?,
    val surface: PaneSurface?
) {
    fun blockFor(graphRoute: String): PaneBlock? = PaneMath.activeBlock(graphRoute, graphs, widthClass)

    fun isOpen(graphRoute: String, column: Int): Boolean =
        surface?.takeIf { it.graphId == graphRoute }?.columns?.getOrNull(column) != null
}

private fun paneColumnLevel(graphRoute: String, column: Int): String = "$graphRoute#pane$column"

private class ContentSlot(
    val entry: NavigationEntry,
    val levels: List<String>,
    val hostedModals: List<NavigationEntry> = emptyList(),
    val zIndex: Float,
    val isEntering: Boolean,
    val animationDecision: AnimationDecision?,
    val progressDriver: TransitionProgressDriver,
    val blockInput: Boolean,
    val clearSemantics: Boolean,
    val resting: Boolean = false,
    val follows: Boolean = false,
    val shielded: Boolean = false
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
    panes: PaneRendering,
    screenWidth: Float,
    screenHeight: Float
) {
    nodes.forEach { node ->
        key(node.key) {
            when (node) {
                is LayoutTreeBranch -> LayoutBranchHost(
                    branch = node,
                    slotsByKey = slotsByKey,
                    panes = panes,
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
    panes: PaneRendering,
    screenWidth: Float,
    screenHeight: Float
) {
    val block = panes.blockFor(branch.route)
    BranchBox(branch, slotsByKey, screenWidth, screenHeight) {
        GraphLayout(panes.graphs[branch.route]) {
            // Everything under one layout occupies the same space and is ordered by zIndex.
            // Handing the children straight to the layout would let a Column or a Scaffold
            // stack the screens one after another instead.
            Box(modifier = Modifier.fillMaxSize()) {
                if (block != null) {
                    PaneBranchContent(branch, block, slotsByKey, panes, screenWidth, screenHeight)
                } else {
                    LayoutTreeNodes(
                        nodes = branch.children,
                        slotsByKey = slotsByKey,
                        panes = panes,
                        screenWidth = screenWidth,
                        screenHeight = screenHeight
                    )
                }
            }
        }
    }
}

@Composable
private fun BranchBox(
    branch: LayoutTreeBranch,
    slotsByKey: Map<String, ContentSlot>,
    screenWidth: Float,
    screenHeight: Float,
    content: @Composable () -> Unit
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
                content()
            }
        }
    }
}

@Composable
private fun PaneBranchContent(
    branch: LayoutTreeBranch,
    block: PaneBlock,
    slotsByKey: Map<String, ContentSlot>,
    panes: PaneRendering,
    screenWidth: Float,
    screenHeight: Float
) {
    val columnNodes = block.columns.indices.map { column ->
        branch.children.firstOrNull { node ->
            node is LayoutTreeBranch && node.route == paneColumnLevel(branch.route, column)
        } as LayoutTreeBranch?
    }
    val columnKeys = columnNodes.mapNotNull { it?.key }.toSet()
    val coveringNodes = branch.children.filter { it.key !in columnKeys }
    val slots = columnNodes.mapIndexed { column, node ->
        val open = panes.isOpen(branch.route, column)
        PaneSlot(open) { modifier, empty ->
            PaneColumnHost(column, node, open, modifier, empty, slotsByKey, panes, screenWidth, screenHeight)
        }
    }
    block.content(slots)
    if (coveringNodes.isNotEmpty()) {
        Box(modifier = Modifier.fillMaxSize()) {
            LayoutTreeNodes(
                nodes = coveringNodes,
                slotsByKey = slotsByKey,
                panes = panes,
                screenWidth = screenWidth,
                screenHeight = screenHeight
            )
        }
    }
}

@Composable
private fun PaneColumnHost(
    column: Int,
    node: LayoutTreeBranch?,
    open: Boolean,
    modifier: Modifier,
    empty: @Composable () -> Unit,
    slotsByKey: Map<String, ContentSlot>,
    panes: PaneRendering,
    screenWidth: Float,
    screenHeight: Float
) {
    BoxWithConstraints(modifier = modifier.clipToBounds()) {
        val columnWidth = if (constraints.hasBoundedWidth) constraints.maxWidth.toFloat() else screenWidth
        val columnHeight = if (constraints.hasBoundedHeight) constraints.maxHeight.toFloat() else screenHeight
        CompositionLocalProvider(LocalPaneColumn provides column) {
            if (!open) {
                empty()
            }
            if (node != null) {
                BranchBox(node, slotsByKey, columnWidth, columnHeight) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        LayoutTreeNodes(
                            nodes = node.children,
                            slotsByKey = slotsByKey,
                            panes = panes,
                            screenWidth = columnWidth,
                            screenHeight = columnHeight
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
                            .zIndex(NavigationZIndex.SYSTEM_BASE + navigatable.elevation)
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
