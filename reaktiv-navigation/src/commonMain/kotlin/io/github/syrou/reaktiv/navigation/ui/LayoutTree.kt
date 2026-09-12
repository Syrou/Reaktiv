package io.github.syrou.reaktiv.navigation.ui

import io.github.syrou.reaktiv.navigation.transition.NavTransition
import io.github.syrou.reaktiv.navigation.util.AnimationDecision
import io.github.syrou.reaktiv.navigation.util.IndicatorAnchor

/**
 * One surface the content layer renders, together with the graph layouts enclosing it.
 *
 * [layoutRoutes] is the full layout chain of the entry, outermost first, and never depends on
 * what the other slots happen to be. That is what keeps a screen's composition path fixed for
 * as long as it is on the back stack.
 */
internal data class LayoutTreeSlot(
    val key: String,
    val layoutRoutes: List<String>,
    val zIndex: Float,
    val indicatorAnchor: IndicatorAnchor? = null,
    val shielded: Boolean = false
)

internal sealed interface LayoutTreeNode {
    val key: String
    val zIndex: Float
}

/**
 * A graph layout. Renders once for every slot beneath it, so a layout two screens have in common
 * is composed a single time without either screen having to be moved to reach it.
 *
 * [ownerSlotKey] is set on the outermost branch that encloses exactly one slot. That branch is
 * where the slot's transition plays, so a graph arriving as a whole animates together with its
 * chrome, while steps taken inside a graph animate underneath chrome that stays put.
 *
 * [indicatorSlotKey] names the slot whose dismiss affordance belongs at this branch, meaning this
 * is chrome the drag would take away along with that slot, so the affordance sits above it rather
 * than inside it where a drag starting on the chrome would miss it.
 */
internal data class LayoutTreeBranch(
    val route: String,
    override val zIndex: Float,
    val ownerSlotKey: String?,
    val indicatorSlotKey: String?,
    val children: List<LayoutTreeNode>
) : LayoutTreeNode {
    override val key: String get() = "layout:$route"
}

internal data class LayoutTreeLeaf(
    val slotKey: String,
    override val zIndex: Float,
    val ownsTransition: Boolean,
    val ownsIndicator: Boolean
) : LayoutTreeNode {
    override val key: String get() = "slot:$slotKey"
}

/** Input blocker drawn directly over the slot a gesture is revealing. */
internal data class LayoutTreeShield(
    val slotKey: String,
    override val zIndex: Float
) : LayoutTreeNode {
    override val key: String get() = "shield:$slotKey"
}

/**
 * Nests [slots] under their graph layouts, sharing every layout the slots have in common.
 *
 * The resulting path down to a given slot is a function of that slot alone, so slots appearing
 * and disappearing around it never relocate it.
 *
 * [slots] are given back to front. Where several of them anchor their dismiss affordance at the
 * same layout, the last one offering a drag holds it, so the affordance belongs to the surface in
 * front and stays put for as long as one of them is still there.
 *
 * Usage:
 * ```kotlin
 * val tree = buildLayoutTree(
 *     listOf(
 *         LayoutTreeSlot(key = "exiting", layoutRoutes = listOf("wallet"), zIndex = 2f),
 *         LayoutTreeSlot(key = "entering", layoutRoutes = listOf("wallet"), zIndex = 3f)
 *     )
 * )
 * ```
 */
internal fun buildLayoutTree(slots: List<LayoutTreeSlot>): List<LayoutTreeNode> {
    // Where a slot's transition plays is the one question that depends on the whole set, so it is
    // answered for every slot up front. Where its affordance sits depends on that slot alone and is
    // read straight off it.
    val anchored = slots.mapIndexed { index, slot ->
        AnchoredSlot(
            slot = slot,
            transitionAnchorRoute = outermostUnsharedLayout(
                layoutRoutes = slot.layoutRoutes,
                sharedWith = slots.filterIndexed { other, _ -> other != index }.map { it.layoutRoutes }
            )
        )
    }
    return buildLayoutLevel(anchored, depth = 0)
}

private class AnchoredSlot(
    val slot: LayoutTreeSlot,
    val transitionAnchorRoute: String?
)

private fun LayoutTreeSlot.holdsIndicatorAt(route: String?): Boolean = when (route) {
    null -> indicatorAnchor == IndicatorAnchor.OwnContent
    else -> indicatorAnchor == IndicatorAnchor.Layout(route)
}

private fun buildLayoutLevel(slots: List<AnchoredSlot>, depth: Int): List<LayoutTreeNode> {
    val nodes = mutableListOf<LayoutTreeNode>()
    val branched = mutableSetOf<String>()
    slots.forEach { anchored ->
        val slot = anchored.slot
        if (slot.layoutRoutes.size <= depth) {
            nodes += LayoutTreeLeaf(
                slotKey = slot.key,
                zIndex = slot.zIndex,
                ownsTransition = anchored.transitionAnchorRoute == null,
                ownsIndicator = slot.holdsIndicatorAt(null)
            )
            if (slot.shielded) {
                nodes += LayoutTreeShield(slot.key, NavigationZIndex.CONTENT_REVEALED_SHIELD)
            }
            return@forEach
        }
        val route = slot.layoutRoutes[depth]
        if (!branched.add(route)) return@forEach
        val group = slots.filter { it.slot.layoutRoutes.getOrNull(depth) == route }
        nodes += LayoutTreeBranch(
            route = route,
            zIndex = group.maxOf { it.slot.zIndex },
            ownerSlotKey = group.lastOrNull { it.transitionAnchorRoute == route }?.slot?.key,
            indicatorSlotKey = group.lastOrNull { it.slot.holdsIndicatorAt(route) }?.slot?.key,
            children = buildLayoutLevel(group, depth + 1)
        )
    }
    return nodes
}

internal data class TransitionPlacement(
    val currentZIndex: Float,
    val previousZIndex: Float,
    val currentPlaysTransition: Boolean
)

/**
 * How the screen arriving and the screen leaving are stacked against each other, and whether the
 * arriving one plays a transition of its own.
 *
 * Lifting the leaving screen clear of everything only applies when the two sit under different
 * chrome, because an exit that animates out of one graph and into another has nothing to slide
 * against inside a layout they both stand in. When they do share chrome, the leaving screen is
 * lifted above the arriving one inside it, and the arriving one is simply revealed underneath
 * rather than playing an entrance nobody can see.
 *
 * Usage:
 * ```kotlin
 * val placement = decideTransitionPlacement(
 *     currentLayoutRoutes = listOf("wallet"),
 *     previousLayoutRoutes = listOf("home"),
 *     decision = animationDecision
 * )
 * ```
 */
internal fun decideTransitionPlacement(
    currentLayoutRoutes: List<String>,
    previousLayoutRoutes: List<String>?,
    decision: AnimationDecision?
): TransitionPlacement {
    val sharedChrome = previousLayoutRoutes?.let {
        sharedLayoutDepth(currentLayoutRoutes, listOf(it))
    } ?: 0
    val layoutChanged = previousLayoutRoutes != null &&
        sharedChrome < maxOf(currentLayoutRoutes.size, previousLayoutRoutes.size)
    val liftExiting = layoutChanged && decision?.shouldAnimateExit == true
    val exitDrawsOnTop = !liftExiting &&
        decision != null &&
        decision.enterTransition is NavTransition.None &&
        decision.exitTransition !is NavTransition.None
    return TransitionPlacement(
        currentZIndex = if (exitDrawsOnTop) {
            NavigationZIndex.CONTENT_BACK
        } else {
            NavigationZIndex.CONTENT_FRONT
        },
        previousZIndex = when {
            liftExiting -> NavigationZIndex.CONTENT_LIFTED_EXIT
            exitDrawsOnTop -> NavigationZIndex.CONTENT_FRONT
            else -> NavigationZIndex.CONTENT_BACK
        },
        currentPlaysTransition = !(liftExiting && sharedChrome > 0)
    )
}

/**
 * How many layouts, counting from the outermost inwards, [layoutRoutes] has in common with whichever
 * of [sharedWith] it agrees with furthest.
 */
internal fun sharedLayoutDepth(
    layoutRoutes: List<String>,
    sharedWith: List<List<String>>
): Int = sharedWith.maxOfOrNull { other ->
    layoutRoutes.zip(other).takeWhile { (route, otherRoute) -> route == otherRoute }.size
} ?: 0

/**
 * The outermost layout in [layoutRoutes] that nothing in [sharedWith] has, or null when every layout
 * the surface sits under also encloses one of them.
 *
 * This is where a surface begins. Chrome the screen does not share with the screens beside it
 * arrived together with it and animates with it, while chrome they all stand under was already on
 * screen and stays put.
 */
internal fun outermostUnsharedLayout(
    layoutRoutes: List<String>,
    sharedWith: List<List<String>>
): String? = layoutRoutes.getOrNull(sharedLayoutDepth(layoutRoutes, sharedWith))
