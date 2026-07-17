package io.github.syrou.reaktiv.navigation.util

import io.github.syrou.reaktiv.navigation.NavigationModule
import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.definition.LoadingModal
import io.github.syrou.reaktiv.navigation.layer.RenderLayer
import io.github.syrou.reaktiv.navigation.model.NavigationEntry
import io.github.syrou.reaktiv.navigation.transition.GestureAxis
import io.github.syrou.reaktiv.navigation.transition.popExitSpec
import io.github.syrou.reaktiv.navigation.transition.presentationAxis

internal fun canHandleBack(state: NavigationState, navModule: NavigationModule): Boolean {
    if (state.isBootstrapping) return false
    if (state.isEvaluatingNavigation) return false
    if (!state.canGoBack) return false
    if (state.currentEntry.navigatable is LoadingModal) return false
    return true
}

internal fun revealedEntryForBack(state: NavigationState): NavigationEntry? {
    val ordered = state.orderedBackStack
    return ordered.getOrNull(ordered.size - 2)
}

internal fun canArmInteractiveBackGesture(state: NavigationState, navModule: NavigationModule): Boolean {
    if (!canHandleBack(state, navModule)) return false
    val top = state.currentEntry.navigatable
    if (top.renderLayer != RenderLayer.CONTENT) return false
    if (!top.backGestureEnabled) return false
    val popAxis = popExitSpec(top)?.transition?.presentationAxis() ?: GestureAxis.Neutral
    if (popAxis == GestureAxis.Vertical) return false
    return revealedContentEntryAvailable(state, navModule)
}

internal fun canArmSwipeDismiss(state: NavigationState, navModule: NavigationModule): Boolean {
    if (!canHandleBack(state, navModule)) return false
    val top = state.currentEntry.navigatable
    if (top.renderLayer != RenderLayer.CONTENT) return false
    if (!top.swipeToDismiss) return false
    return revealedContentEntryAvailable(state, navModule)
}

private fun revealedContentEntryAvailable(state: NavigationState, navModule: NavigationModule): Boolean {
    val revealed = revealedEntryForBack(state) ?: return false
    val revealedNavigatable = revealed.navigatable
    if (revealedNavigatable.renderLayer != RenderLayer.CONTENT) return false
    if (state.activeModalContexts[revealed.path] != null) return false
    return true
}
