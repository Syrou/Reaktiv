package io.github.syrou.reaktiv.navigation.ui

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
    val indicatorAnchorRoute: String? = null,
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
 * [indicatorSlotKey] names the slot whose dismiss affordance belongs at this branch, which is the
 * outermost chrome that would leave with that slot when it is dragged away.
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
 * same layout, the last one holds it, so the affordance belongs to the surface in front.
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
internal fun buildLayoutTree(slots: List<LayoutTreeSlot>): List<LayoutTreeNode> =
    buildLayoutLevel(slots, depth = 0, transitionOwned = false)

private fun buildLayoutLevel(
    slots: List<LayoutTreeSlot>,
    depth: Int,
    transitionOwned: Boolean
): List<LayoutTreeNode> {
    val nodes = mutableListOf<LayoutTreeNode>()
    val branched = mutableSetOf<String>()
    slots.forEach { slot ->
        if (slot.layoutRoutes.size <= depth) {
            nodes += LayoutTreeLeaf(
                slotKey = slot.key,
                zIndex = slot.zIndex,
                ownsTransition = !transitionOwned,
                ownsIndicator = slot.indicatorAnchorRoute == null
            )
            if (slot.shielded) {
                nodes += LayoutTreeShield(slot.key, NavigationZIndex.CONTENT_REVEALED_SHIELD)
            }
            return@forEach
        }
        val route = slot.layoutRoutes[depth]
        if (!branched.add(route)) return@forEach
        val group = slots.filter { it.layoutRoutes.getOrNull(depth) == route }
        val owner = if (transitionOwned) null else group.singleOrNull()
        nodes += LayoutTreeBranch(
            route = route,
            zIndex = group.maxOf { it.zIndex },
            ownerSlotKey = owner?.key,
            indicatorSlotKey = group.lastOrNull { it.indicatorAnchorRoute == route }?.key,
            children = buildLayoutLevel(group, depth + 1, transitionOwned || owner != null)
        )
    }
    return nodes
}

internal data class ExitPlacement(
    val liftExiting: Boolean,
    val sharesChrome: Boolean
)

/**
 * Decides how the screen on its way out is ordered against the one replacing it.
 *
 * Lifting only applies when the two sit under different chrome, because an exit that animates
 * out of one graph and into another has nothing to slide against inside a common layout.
 */
internal fun decideExitPlacement(
    currentLayoutRoutes: List<String>,
    previousLayoutRoutes: List<String>?,
    shouldAnimateExit: Boolean
): ExitPlacement {
    if (previousLayoutRoutes == null) {
        return ExitPlacement(liftExiting = false, sharesChrome = false)
    }
    val sharedPrefix = currentLayoutRoutes.zip(previousLayoutRoutes)
        .takeWhile { (current, previous) -> current == previous }
        .size
    return ExitPlacement(
        liftExiting = previousLayoutRoutes != currentLayoutRoutes && shouldAnimateExit,
        sharesChrome = sharedPrefix > 0
    )
}

/**
 * The outermost layout in [layoutRoutes] that nothing in [staysBehind] has, or null when every
 * layout the surface sits under also encloses something that remains once the surface is gone.
 *
 * This is where a surface begins. Chrome it does not share is part of it and leaves with it, so a
 * dismiss affordance belongs above that chrome. Chrome it shares belongs to what is underneath and
 * stays put, so the affordance belongs below it.
 */
internal fun outermostUnsharedLayout(
    layoutRoutes: List<String>,
    staysBehind: List<List<String>>
): String? {
    val shared = staysBehind.maxOfOrNull { other ->
        layoutRoutes.zip(other).takeWhile { (route, otherRoute) -> route == otherRoute }.size
    } ?: return null
    return layoutRoutes.getOrNull(shared)
}
