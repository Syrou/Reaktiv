package io.github.syrou.reaktiv.navigation.ui

import androidx.compose.runtime.State
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import io.github.syrou.reaktiv.core.Store
import io.github.syrou.reaktiv.core.util.selectState
import io.github.syrou.reaktiv.navigation.NavigationModule
import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.definition.DismissSource
import io.github.syrou.reaktiv.navigation.definition.Modal
import io.github.syrou.reaktiv.navigation.definition.allowsDismiss
import io.github.syrou.reaktiv.navigation.model.NavigationEntry
import io.github.syrou.reaktiv.navigation.util.canArmInteractiveBackGesture
import io.github.syrou.reaktiv.navigation.util.canArmSwipeDismiss
import io.github.syrou.reaktiv.navigation.util.canHandleBack
import io.github.syrou.reaktiv.navigation.util.performUserBack
import io.github.syrou.reaktiv.navigation.util.revealedEntryForDismiss
import kotlinx.coroutines.flow.first

internal class ScrubAxis(
    val extent: Float,
    private val origin: Float,
    private val sign: Float,
    val isVertical: Boolean
) {
    private fun select(offset: Offset): Float = if (isVertical) offset.y else offset.x

    fun along(delta: Offset): Float = sign * select(delta)

    fun across(delta: Offset): Float = if (isVertical) delta.x else delta.y

    fun towards(amount: Float): Boolean = sign * amount > 0f

    fun offsetOf(along: Float): Offset = if (isVertical) Offset(0f, sign * along) else Offset(sign * along, 0f)

    fun progressAt(position: Offset): Float = sign * (select(position) - origin) / extent

    fun toProgressVelocity(axisVelocity: Float): Float = axisVelocity / extent

    companion object {
        fun horizontal(width: Float, isLtr: Boolean, originX: Float = 0f): ScrubAxis =
            ScrubAxis(width, originX, if (isLtr) 1f else -1f, isVertical = false)

        fun vertical(height: Float, originY: Float = 0f): ScrubAxis =
            ScrubAxis(height, originY, 1f, isVertical = true)

        fun horizontal(down: PointerInputChange, width: Float, isLtr: Boolean): ScrubAxis =
            horizontal(width, isLtr, down.position.x)

        fun vertical(down: PointerInputChange, height: Float): ScrubAxis =
            vertical(height, down.position.y)
    }
}

internal class ScrubOutcome(val commit: Boolean, val progressVelocity: Float)

internal fun armContentBack(
    state: NavigationState,
    navModule: NavigationModule,
    controller: InteractiveTransitionController
): InteractiveTransitionController.ScrubKind? {
    if (!canArmInteractiveBackGesture(state, navModule)) return null
    if (controller.contentTransitionActive) return null
    val revealed = state.revealedEntry ?: return null
    return InteractiveTransitionController.ScrubKind.ContentBack(state.currentEntry, revealed)
}

internal fun armContentDismiss(
    state: NavigationState,
    navModule: NavigationModule,
    controller: InteractiveTransitionController
): InteractiveTransitionController.ScrubKind? {
    if (!canArmSwipeDismiss(state, navModule)) return null
    if (controller.contentTransitionActive) return null
    val revealed = revealedEntryForDismiss(state, navModule) ?: return null
    return InteractiveTransitionController.ScrubKind.ContentDismiss(state.currentEntry, revealed)
}

internal fun armModalDismiss(
    state: NavigationState,
    modal: NavigationEntry
): InteractiveTransitionController.ScrubKind? {
    val navigatable = modal.navigatable as? Modal ?: return null
    if (!navigatable.dismissal.swipe.allowsDismiss) return null
    if (!canHandleBack(state)) return null
    return InteractiveTransitionController.ScrubKind.ModalDismiss(modal)
}

internal suspend fun AwaitPointerEventScope.trackScrub(
    controller: InteractiveTransitionController,
    latestState: State<NavigationState>,
    down: PointerInputChange,
    slopChange: PointerInputChange,
    axis: ScrubAxis,
    velocityThresholdPx: Float,
    pumpDrag: suspend AwaitPointerEventScope.(onDrag: (PointerInputChange) -> Unit) -> Unit
): ScrubOutcome {
    val topKey = controller.scrubKind?.top?.stableKey
    val velocityTracker = VelocityTracker()
    velocityTracker.addPosition(down.uptimeMillis, down.position)
    velocityTracker.addPosition(slopChange.uptimeMillis, slopChange.position)

    controller.scrubTo(axis.progressAt(slopChange.position))

    var invalidated = false
    pumpDrag { change ->
        velocityTracker.addPosition(change.uptimeMillis, change.position)
        if (latestState.value.currentEntry.stableKey != topKey) {
            invalidated = true
        }
        if (!invalidated) {
            controller.scrubTo(axis.progressAt(change.position))
        }
        change.consume()
    }

    val axisVelocity = axis.along(velocityTracker.calculateVelocity().let { Offset(it.x, it.y) })
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
        val event = awaitPointerEvent(PointerEventPass.Initial)
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
    navModule: NavigationModule
) {
    try {
        val kind = controller.scrubKind ?: return
        val top = kind.top
        val source = if (kind is InteractiveTransitionController.ScrubKind.ContentBack) {
            DismissSource.Back
        } else {
            DismissSource.Swipe
        }
        controller.settle(commit = commit, initialVelocity = progressVelocity)
        if (!commit) {
            return
        }
        val state = store.selectState<NavigationState>().first()
        val stillValid = state.currentEntry.stableKey == top.stableKey && canHandleBack(state)
        if (!stillValid) {
            return
        }
        performUserBack(store, navModule, top, kind.revealed, source = source, expectedTopKey = top.stableKey)
        val after = store.selectState<NavigationState>().first()
        if (after.currentEntry.stableKey == top.stableKey) {
            controller.settle(commit = false)
        } else {
            controller.markLanded()
        }
    } finally {
        controller.reset()
    }
}
