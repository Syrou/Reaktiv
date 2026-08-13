package io.github.syrou.reaktiv.navigation.ui

import androidx.compose.runtime.State
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import io.github.syrou.reaktiv.core.Store
import io.github.syrou.reaktiv.core.util.selectState
import io.github.syrou.reaktiv.navigation.extension.navigateBack
import kotlinx.coroutines.flow.first
import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.model.NavigationEntry

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
    top: NavigationEntry,
    revealed: NavigationEntry?
) {
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
        val dismissHandler = top.navigatable.onDismissRequest
        if (dismissHandler != null) {
            dismissHandler.invoke(store)
        } else {
            store.navigateBack(expectedTopKey = top.stableKey)
        }
        val after = store.selectState<NavigationState>().first()
        if (after.currentEntry.stableKey == top.stableKey) {
            controller.settle(commit = false)
        }
    } finally {
        controller.reset()
    }
}
