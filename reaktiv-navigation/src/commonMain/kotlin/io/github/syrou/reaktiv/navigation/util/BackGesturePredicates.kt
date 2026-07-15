package io.github.syrou.reaktiv.navigation.util

import io.github.syrou.reaktiv.navigation.NavigationModule
import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.definition.LoadingModal
import io.github.syrou.reaktiv.navigation.layer.RenderLayer
import io.github.syrou.reaktiv.navigation.model.NavigationEntry
import io.github.syrou.reaktiv.navigation.transition.GestureAxis
import io.github.syrou.reaktiv.navigation.transition.NavTransition
import io.github.syrou.reaktiv.navigation.transition.presentationAxis

internal fun canHandleBack(state: NavigationState, navModule: NavigationModule): Boolean {
    if (state.isBootstrapping) return false
    if (state.isEvaluatingNavigation) return false
    if (!state.canGoBack) return false
    if (navModule.resolveNavigatable(state.currentEntry) is LoadingModal) return false
    return true
}

internal fun revealedEntryForBack(state: NavigationState): NavigationEntry? {
    val ordered = state.orderedBackStack
    return ordered.getOrNull(ordered.size - 2)
}

internal fun canArmInteractiveBackGesture(state: NavigationState, navModule: NavigationModule): Boolean {
    if (!canHandleBack(state, navModule)) return false
    val top = navModule.resolveNavigatable(state.currentEntry) ?: return false
    if (top.renderLayer != RenderLayer.CONTENT) return false
    if (!top.backGestureEnabled) return false
    val popAxis = (top.popExitTransition ?: top.enterTransition)
        .takeUnless { it == NavTransition.None }
        ?.presentationAxis()
        ?: GestureAxis.Neutral
    if (popAxis == GestureAxis.Vertical) return false
    return revealedContentEntryAvailable(state, navModule)
}

internal fun canArmSwipeDismiss(state: NavigationState, navModule: NavigationModule): Boolean {
    if (!canHandleBack(state, navModule)) return false
    val top = navModule.resolveNavigatable(state.currentEntry) ?: return false
    if (top.renderLayer != RenderLayer.CONTENT) return false
    if (!top.swipeToDismiss) return false
    return revealedContentEntryAvailable(state, navModule)
}

private fun revealedContentEntryAvailable(state: NavigationState, navModule: NavigationModule): Boolean {
    val revealed = revealedEntryForBack(state) ?: return false
    val revealedNavigatable = navModule.resolveNavigatable(revealed) ?: return false
    if (revealedNavigatable.renderLayer != RenderLayer.CONTENT) return false
    if (state.activeModalContexts[revealed.path] != null) return false
    return true
}
