package io.github.syrou.reaktiv.navigation.ui

import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.ScrubState
import io.github.syrou.reaktiv.navigation.ScrubType
import io.github.syrou.reaktiv.navigation.model.NavigationEntry

internal suspend fun driveControllerFromScrubState(
    controller: InteractiveTransitionController,
    state: NavigationState
) {
    val scrub = state.activeScrub
    if (scrub != null) {
        if (controller.isReplicating) {
            controller.scrubTo(scrub.progress)
            return
        }
        if (controller.phase != InteractiveTransitionController.Phase.Idle) return
        val kind = resolveScrubKind(state, scrub) ?: return
        if (!controller.beginScrub(kind, InteractiveTransitionController.Source.Replicated)) return
        controller.scrubTo(scrub.progress)
    } else if (controller.isReplicating) {
        val topKey = controller.scrubKind?.top?.stableKey
        val topGone = topKey != null && state.orderedBackStack.none { it.stableKey == topKey }
        if (!topGone) {
            controller.settle(commit = false)
        }
        controller.reset()
    }
}

private fun resolveScrubKind(
    state: NavigationState,
    scrub: ScrubState
): InteractiveTransitionController.ScrubKind? {
    val entries = state.orderedBackStack
    fun byKey(key: String?): NavigationEntry? = key?.let { k -> entries.firstOrNull { it.stableKey == k } }
    val top = byKey(scrub.topKey) ?: return null
    val revealed = byKey(scrub.revealedKey)
    return when (scrub.type) {
        ScrubType.Back -> revealed?.let { InteractiveTransitionController.ScrubKind.ContentBack(top, it) }
        ScrubType.Dismiss -> InteractiveTransitionController.ScrubKind.ContentDismiss(top, revealed)
        ScrubType.ModalDismiss -> InteractiveTransitionController.ScrubKind.ModalDismiss(top)
    }
}
