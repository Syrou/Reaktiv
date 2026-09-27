package io.github.syrou.reaktiv.navigation.util

import io.github.syrou.reaktiv.navigation.NavigationModule
import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.definition.DismissIndicatorPlacement
import io.github.syrou.reaktiv.navigation.definition.Graph
import io.github.syrou.reaktiv.navigation.definition.LoadingModal
import io.github.syrou.reaktiv.navigation.definition.Navigatable
import io.github.syrou.reaktiv.navigation.definition.Modal
import io.github.syrou.reaktiv.navigation.definition.NavigationGraph
import io.github.syrou.reaktiv.navigation.definition.allowsDismiss
import io.github.syrou.reaktiv.navigation.layer.RenderLayer
import io.github.syrou.reaktiv.navigation.model.NavigationEntry
import io.github.syrou.reaktiv.navigation.transition.GestureAxis
import io.github.syrou.reaktiv.navigation.transition.gestureAxis

internal fun canHandleBack(state: NavigationState): Boolean =
    state.canGoBack && (state.currentEntry.navigatable.isSystemOverlay || !backBlockedByWork(state))

internal fun backBlockedByWork(state: NavigationState): Boolean =
    state.currentEntry.navigatable is LoadingModal || state.isBootstrapping || state.isEvaluatingNavigation

internal val Navigatable.isSystemOverlay: Boolean
    get() = renderLayer == RenderLayer.SYSTEM && this !is LoadingModal

/**
 * The innermost graph containing [entry] that presents itself as a draggable surface.
 *
 * Innermost wins: with a sheet presented inside another sheet, the gesture takes away the one
 * being looked at rather than both.
 */
internal fun dismissableBoundary(
    entry: NavigationEntry,
    navModule: NavigationModule
): String? = dismissableBoundary(entry, navModule.getGraphDefinitions())

internal fun dismissableBoundary(
    entry: NavigationEntry,
    graphDefinitions: Map<String, NavigationGraph>
): String? = entry.graphChain.lastOrNull { graphId ->
    graphDefinitions[graphId]?.declaration?.dismissal?.swipe?.allowsDismiss == true
}

internal fun dismissableSurface(entry: NavigationEntry, navModule: NavigationModule): Graph? =
    dismissableBoundary(entry, navModule)
        ?.let { navModule.getGraphDefinitions()[it]?.declaration }

/**
 * The entry a dismiss gesture would reveal, skipping everything inside a dismissable boundary.
 *
 * A sheet-presented graph leaves as a unit, so the drag reveals whatever sat beneath the whole
 * graph rather than the previous step within it.
 */
internal fun revealedEntryForDismiss(
    state: NavigationState,
    navModule: NavigationModule
): NavigationEntry? {
    val boundary = dismissableBoundary(state.currentEntry, navModule)
        ?: return state.revealedEntry
    val ordered = state.orderedBackStack
    return ordered.lastOrNull { candidate ->
        dismissableBoundary(candidate, navModule) != boundary
    }
}

internal fun canArmInteractiveBackGesture(state: NavigationState, navModule: NavigationModule): Boolean {
    if (!canHandleBack(state)) return false
    val top = state.currentEntry.navigatable
    if (top is Modal) return false
    if (top.renderLayer != RenderLayer.CONTENT) return false
    if (!top.backGestureEnabled) return false
    if (!top.dismissal.back.allowsDismiss) return false

    val revealed = state.revealedEntry ?: return false
    // Read the axis from whatever would actually move. Going back from the first screen of a
    // presented graph leaves the graph, so a sheet that arrived vertically leaves vertically and
    // the horizontal edge swipe must not arm for it. Deeper inside the graph nothing is crossed,
    // so the screen decides and the edge swipe works normally between steps.
    val departing = presentationSourceFor(
        from = revealed,
        to = state.currentEntry,
        navigatable = top,
        navModule = navModule
    )
    if (departing.gestureAxis() == GestureAxis.Vertical) return false
    return revealedContentEntryAvailable(state)
}

/**
 * Whether [entry] is a surface that carries the grab affordance, decided by what the entry is
 * rather than by what currently sits beneath it.
 *
 * This is the layout question and it is deliberately separate from [canArmSwipeDismiss]. The
 * strip a sheet reserves for its grabber has to be there on every visit, because whatever sits
 * under that strip, the chrome of the surface being dragged or the screen itself, has offset its
 * own layout to match. Deciding it by whether the drag can arm right now moved that chrome by the
 * strip's height whenever the entry underneath was a modal, and again while the screen was on its
 * way out.

 */
internal fun presentsDismissIndicator(entry: NavigationEntry, navModule: NavigationModule): Boolean {
    val top = entry.navigatable
    if (top.renderLayer != RenderLayer.CONTENT) return false
    if (!top.showsDismissIndicator) return false
    val surface = dismissableSurface(entry, navModule) ?: return top.dismissal.swipe.allowsDismiss
    return surface.showsDismissIndicator
}

internal sealed interface IndicatorAnchor {
    object OwnContent : IndicatorAnchor

    data class Layout(val route: String) : IndicatorAnchor
}

internal fun dismissIndicatorAnchor(
    entry: NavigationEntry,
    navModule: NavigationModule,
    layoutRoutes: List<String>
): IndicatorAnchor? {
    if (!presentsDismissIndicator(entry, navModule)) return null
    val surface = dismissableSurface(entry, navModule)
    val placement = surface?.dismissIndicatorPlacement ?: entry.navigatable.dismissIndicatorPlacement
    val anchorRoute = when (placement) {
        DismissIndicatorPlacement.OutermostChrome -> layoutRoutes.firstOrNull()
        DismissIndicatorPlacement.Surface -> {
            val boundary = dismissableBoundary(entry, navModule) ?: return IndicatorAnchor.OwnContent
            val chain = entry.graphChain
            val boundaryDepth = chain.lastIndexOf(boundary)
            layoutRoutes.firstOrNull { chain.indexOf(it) >= boundaryDepth }
        }
    }
    return anchorRoute?.let { IndicatorAnchor.Layout(it) } ?: IndicatorAnchor.OwnContent
}

internal fun canArmSwipeDismiss(state: NavigationState, navModule: NavigationModule): Boolean {
    if (!canHandleBack(state)) return false
    val top = state.currentEntry.navigatable
    if (top is Modal) return false
    if (top.renderLayer != RenderLayer.CONTENT) return false
    // Inside a dismissable graph the surface, not the screen, decides. A step that navigates
    // horizontally within the graph is still part of a sheet that can be dragged away.
    val insideDismissableGraph = dismissableBoundary(state.currentEntry, navModule) != null
    if (!top.dismissal.swipe.allowsDismiss && !insideDismissableGraph) return false
    val revealed = revealedEntryForDismiss(state, navModule) ?: return false
    return revealed.navigatable.renderLayer == RenderLayer.CONTENT
}

private fun revealedContentEntryAvailable(state: NavigationState): Boolean {
    val revealed = state.revealedEntry ?: return false
    return revealed.navigatable.renderLayer == RenderLayer.CONTENT
}
