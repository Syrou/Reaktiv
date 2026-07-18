package io.github.syrou.reaktiv.navigation.ui

import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.ScrubState
import io.github.syrou.reaktiv.navigation.model.NavigationEntry

internal suspend fun driveControllerFromScrubState(
    controller: InteractiveTransitionController,
    state: NavigationState
) {
    val scrub = state.activeScrub
    if (scrub != null) {
        if (controller.externallyDriven) {
            controller.scrubTo(scrub.progress)
            return
        }
        if (controller.phase != InteractiveTransitionController.Phase.Idle) return
        val kind = resolveScrubKind(state, scrub) ?: return
        controller.externallyDriven = true
        if (!controller.beginScrub(kind)) {
            controller.externallyDriven = false
            return
        }
        when (kind) {
            is InteractiveTransitionController.ScrubKind.ContentBack ->
                controller.armHandoff(kind.topEntry.stableKey, kind.revealedEntry.stableKey)
            is InteractiveTransitionController.ScrubKind.ContentDismiss ->
                kind.revealedEntry?.let { controller.armHandoff(kind.topEntry.stableKey, it.stableKey) }
            is InteractiveTransitionController.ScrubKind.ModalDismiss ->
                controller.armModalHandoff(kind.modalEntry.stableKey)
        }
        controller.scrubTo(scrub.progress)
    } else if (controller.externallyDriven) {
        val kind = controller.scrubKind
        val topKey = when (kind) {
            is InteractiveTransitionController.ScrubKind.ContentBack -> kind.topEntry.stableKey
            is InteractiveTransitionController.ScrubKind.ContentDismiss -> kind.topEntry.stableKey
            is InteractiveTransitionController.ScrubKind.ModalDismiss -> kind.modalEntry.stableKey
            null -> null
        }
        val topGone = topKey != null && state.orderedBackStack.none { it.stableKey == topKey }
        if (!topGone) {
            controller.clearHandoffs()
            controller.settle(commit = false)
        }
        controller.reset()
        controller.externallyDriven = false
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
    return when (scrub.kind) {
        "back-scrub" -> revealed?.let { InteractiveTransitionController.ScrubKind.ContentBack(top, it) }
        "dismiss-scrub" -> InteractiveTransitionController.ScrubKind.ContentDismiss(top, revealed)
        "modal-dismiss-scrub" -> InteractiveTransitionController.ScrubKind.ModalDismiss(top)
        else -> null
    }
}
