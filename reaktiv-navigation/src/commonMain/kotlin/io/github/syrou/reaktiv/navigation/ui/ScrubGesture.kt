package io.github.syrou.reaktiv.navigation.ui

import io.github.syrou.reaktiv.navigation.definition.DismissSource
import androidx.compose.runtime.State
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import io.github.syrou.reaktiv.core.Store
import io.github.syrou.reaktiv.core.util.selectState
import io.github.syrou.reaktiv.navigation.extension.navigateBack
import kotlinx.coroutines.flow.first
import io.github.syrou.reaktiv.navigation.NavigationModule
import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.model.NavigationEntry
import io.github.syrou.reaktiv.navigation.util.revealedEntryForDismiss
import io.github.syrou.reaktiv.navigation.util.revealedEntryForBack
import io.github.syrou.reaktiv.navigation.util.canArmSwipeDismiss
import io.github.syrou.reaktiv.navigation.util.canArmInteractiveBackGesture

internal class ScrubAxis(
    private val extent: Float,
    private val origin: Float,
    private val sign: Float,
    private val select: (Offset) -> Float
) {
    fun progressAt(position: Offset): Float = sign * (select(position) - origin) / extent

    fun velocityFrom(velocity: Offset): Float = sign * select(velocity)

    fun toProgressVelocity(axisVelocity: Float): Float = axisVelocity / extent

    companion object {
        fun horizontal(down: PointerInputChange, width: Float, isLtr: Boolean): ScrubAxis =
            ScrubAxis(width, down.position.x, if (isLtr) 1f else -1f) { it.x }

        fun vertical(down: PointerInputChange, height: Float): ScrubAxis =
            ScrubAxis(height, down.position.y, 1f) { it.y }
    }
}

internal class ScrubOutcome(val commit: Boolean, val progressVelocity: Float)

/**
 * The entries a scrub would move, and the kind of scrub it is.
 *
 * Produced by [armContentBack] and [armContentDismiss] so that every gesture path asks the same
 * question in the same order. Six call sites previously repeated this decision inline, which is
 * how a rule change reaches five of them.
 */
internal class ScrubArming(
    val top: NavigationEntry,
    val revealed: NavigationEntry,
    val kind: InteractiveTransitionController.ScrubKind
)

internal fun armContentBack(
    state: NavigationState,
    navModule: NavigationModule,
    controller: InteractiveTransitionController
): ScrubArming? {
    if (!canArmInteractiveBackGesture(state, navModule)) return null
    if (controller.contentTransitionActive) return null
    val top = state.currentEntry
    val revealed = revealedEntryForBack(state) ?: return null
    return ScrubArming(top, revealed, InteractiveTransitionController.ScrubKind.ContentBack(top, revealed))
}

internal fun armContentDismiss(
    state: NavigationState,
    navModule: NavigationModule,
    controller: InteractiveTransitionController
): ScrubArming? {
    if (!canArmSwipeDismiss(state, navModule)) return null
    if (controller.contentTransitionActive) return null
    val top = state.currentEntry
    val revealed = revealedEntryForDismiss(state, navModule) ?: return null
    return ScrubArming(top, revealed, InteractiveTransitionController.ScrubKind.ContentDismiss(top, revealed))
}


internal suspend fun AwaitPointerEventScope.trackScrub(
    controller: InteractiveTransitionController,
    latestState: State<NavigationState>,
    top: NavigationEntry,
    down: PointerInputChange,
    slopChange: PointerInputChange,
    axis: ScrubAxis,
    velocityThresholdPx: Float,
    pumpDrag: suspend AwaitPointerEventScope.(onDrag: (PointerInputChange) -> Unit) -> Unit
): ScrubOutcome {
    val velocityTracker = VelocityTracker()
    velocityTracker.addPosition(down.uptimeMillis, down.position)
    velocityTracker.addPosition(slopChange.uptimeMillis, slopChange.position)

    controller.scrubTo(axis.progressAt(slopChange.position))

    var invalidated = false
    pumpDrag { change ->
        velocityTracker.addPosition(change.uptimeMillis, change.position)
        if (latestState.value.currentEntry.stableKey != top.stableKey) {
            invalidated = true
        }
        if (!invalidated) {
            controller.scrubTo(axis.progressAt(change.position))
        }
        change.consume()
    }

    val axisVelocity = axis.velocityFrom(
        velocityTracker.calculateVelocity().let { Offset(it.x, it.y) }
    )
    val commit = !invalidated && InteractiveTransitionController.shouldCommit(
        progress = controller.progress,
        velocity = axisVelocity,
        velocityThreshold = velocityThresholdPx
    )
    return ScrubOutcome(commit, axis.toProgressVelocity(axisVelocity))
}

internal suspend fun AwaitPointerEventScope.pumpInitialPassDrag(
    down: PointerInputChange,
    onDrag: (PointerInputChange) -> Unit
) {
    while (true) {
        val event = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
        val change = event.changes.firstOrNull { it.id == down.id } ?: break
        if (!change.pressed) break
        onDrag(change)
    }
}

internal suspend fun completeInteractiveDismiss(
    commit: Boolean,
    progressVelocity: Float,
    controller: InteractiveTransitionController,
    store: Store,
    navModule: NavigationModule,
    top: NavigationEntry,
    revealed: NavigationEntry?
) {
    val source = when (controller.scrubKind) {
        is InteractiveTransitionController.ScrubKind.ContentBack -> DismissSource.Back
        else -> DismissSource.Swipe
    }
    try {
        if (commit && revealed != null) {
            controller.markCommittedTarget(revealed)
        }
        controller.settle(commit = commit, initialVelocity = progressVelocity)
        if (!commit) {
            return
        }
        val state = store.selectState<NavigationState>().first()
        val stillValid = state.currentEntry.stableKey == top.stableKey &&
            state.canGoBack &&
            !state.isEvaluatingNavigation
        if (!stillValid) {
            return
        }
        if (revealed != null) {
            controller.armHandoff(poppedKey = top.stableKey, targetKey = revealed.stableKey)
        } else {
            controller.armModalHandoff(top.stableKey)
        }
        dismissSurface(store, navModule, top, revealed, source = source, expectedTopKey = top.stableKey)
        val after = store.selectState<NavigationState>().first()
        if (after.currentEntry.stableKey == top.stableKey) {
            controller.settle(commit = false)
        }
    } finally {
        controller.reset()
    }
}
