package io.github.syrou.reaktiv.navigation.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.awaitVerticalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.gestures.verticalDrag
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import io.github.syrou.reaktiv.compose.composeState
import io.github.syrou.reaktiv.compose.rememberStore
import io.github.syrou.reaktiv.core.Store
import io.github.syrou.reaktiv.core.util.selectState
import io.github.syrou.reaktiv.navigation.NavigationModule
import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.definition.Modal
import io.github.syrou.reaktiv.navigation.extension.navigateBack
import io.github.syrou.reaktiv.navigation.model.NavigationEntry
import io.github.syrou.reaktiv.navigation.util.canArmInteractiveBackGesture
import io.github.syrou.reaktiv.navigation.util.canArmSwipeDismiss
import io.github.syrou.reaktiv.navigation.util.canHandleBack
import io.github.syrou.reaktiv.navigation.util.revealedEntryForBack
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private const val EDGE_WIDTH_DP = 20

@Composable
internal fun Modifier.backGestureRecognizer(controller: InteractiveTransitionController): Modifier {
    val store = rememberStore()
    val navModule = LocalNavigationModule.current
    val navigationState by composeState<NavigationState>()
    val latestState = rememberUpdatedState(navigationState)
    val layoutDirection = LocalLayoutDirection.current
    val scope = rememberCoroutineScope()

    return this.pointerInput(navModule, layoutDirection) {
        val edgePx = EDGE_WIDTH_DP.dp.toPx()
        val velocityThresholdPx =
            InteractiveTransitionController.COMMIT_VELOCITY_DP_PER_SEC.dp.toPx()
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val width = size.width.toFloat()
            if (width <= 0f) return@awaitEachGesture
            val isLtr = layoutDirection == LayoutDirection.Ltr
            val inEdge = if (isLtr) {
                down.position.x <= edgePx
            } else {
                down.position.x >= width - edgePx
            }
            if (!inEdge) return@awaitEachGesture
            val state = latestState.value
            if (!canArmInteractiveBackGesture(state, navModule)) return@awaitEachGesture
            if (controller.contentTransitionActive) return@awaitEachGesture
            val top = state.currentEntry
            val revealed = revealedEntryForBack(state) ?: return@awaitEachGesture

            val slopChange = awaitHorizontalTouchSlopOrCancellation(down.id) { change, overSlop ->
                val towardsBack = if (isLtr) overSlop > 0f else overSlop < 0f
                if (towardsBack) {
                    change.consume()
                }
            } ?: return@awaitEachGesture

            val kind = InteractiveTransitionController.ScrubKind.ContentBack(top, revealed)
            if (!controller.beginScrub(kind)) return@awaitEachGesture

            val velocityTracker = VelocityTracker()
            velocityTracker.addPosition(down.uptimeMillis, down.position)
            velocityTracker.addPosition(slopChange.uptimeMillis, slopChange.position)

            fun progressOf(x: Float): Float = if (isLtr) {
                (x - down.position.x) / width
            } else {
                (down.position.x - x) / width
            }

            controller.scrubTo(progressOf(slopChange.position.x))

            var invalidated = false
            horizontalDrag(down.id) { change ->
                velocityTracker.addPosition(change.uptimeMillis, change.position)
                if (latestState.value.currentEntry.stableKey != top.stableKey) {
                    invalidated = true
                }
                if (!invalidated) {
                    controller.scrubTo(progressOf(change.position.x))
                }
                change.consume()
            }

            val rawVelocityX = velocityTracker.calculateVelocity().x
            val backVelocity = if (isLtr) rawVelocityX else -rawVelocityX
            val commit = !invalidated && InteractiveTransitionController.shouldCommit(
                progress = controller.progress,
                velocity = backVelocity,
                velocityThreshold = velocityThresholdPx
            )
            val progressVelocity = backVelocity / width
            scope.launch {
                completeContentGesture(commit, progressVelocity, controller, store, navModule, top, revealed)
            }
        }
    }
}

@Composable
internal fun Modifier.dismissGestureRecognizer(controller: InteractiveTransitionController): Modifier {
    val store = rememberStore()
    val navModule = LocalNavigationModule.current
    val navigationState by composeState<NavigationState>()
    val latestState = rememberUpdatedState(navigationState)
    val scope = rememberCoroutineScope()

    return this.pointerInput(navModule) {
        val velocityThresholdPx =
            InteractiveTransitionController.COMMIT_VELOCITY_DP_PER_SEC.dp.toPx()
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val height = size.height.toFloat()
            if (height <= 0f) return@awaitEachGesture
            val state = latestState.value
            if (!canArmSwipeDismiss(state, navModule)) return@awaitEachGesture
            if (controller.contentTransitionActive) return@awaitEachGesture
            val top = state.currentEntry
            val revealed = revealedEntryForBack(state) ?: return@awaitEachGesture

            val slopChange = awaitVerticalTouchSlopOrCancellation(down.id) { change, overSlop ->
                if (overSlop > 0f) {
                    change.consume()
                }
            } ?: return@awaitEachGesture

            val kind = InteractiveTransitionController.ScrubKind.ContentDismiss(top, revealed)
            if (!controller.beginScrub(kind)) return@awaitEachGesture

            val velocityTracker = VelocityTracker()
            velocityTracker.addPosition(down.uptimeMillis, down.position)
            velocityTracker.addPosition(slopChange.uptimeMillis, slopChange.position)

            fun progressOf(y: Float): Float = (y - down.position.y) / height

            controller.scrubTo(progressOf(slopChange.position.y))

            var invalidated = false
            verticalDrag(down.id) { change ->
                velocityTracker.addPosition(change.uptimeMillis, change.position)
                if (latestState.value.currentEntry.stableKey != top.stableKey) {
                    invalidated = true
                }
                if (!invalidated) {
                    controller.scrubTo(progressOf(change.position.y))
                }
                change.consume()
            }

            val downVelocity = velocityTracker.calculateVelocity().y
            val commit = !invalidated && InteractiveTransitionController.shouldCommit(
                progress = controller.progress,
                velocity = downVelocity,
                velocityThreshold = velocityThresholdPx
            )
            val progressVelocity = downVelocity / height
            scope.launch {
                completeContentGesture(commit, progressVelocity, controller, store, navModule, top, revealed)
            }
        }
    }
}

@Composable
internal fun Modifier.gestureNestedScrollHandoff(controller: InteractiveTransitionController): Modifier {
    val store = rememberStore()
    val navModule = LocalNavigationModule.current
    val navigationState by composeState<NavigationState>()
    val latestState = rememberUpdatedState(navigationState)
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val latestLayoutDirection = rememberUpdatedState(layoutDirection)
    val connection = remember(navModule) {
        val velocityThresholdPx = with(density) {
            InteractiveTransitionController.COMMIT_VELOCITY_DP_PER_SEC.dp.toPx()
        }
        GestureNestedScrollConnection(
            controller = controller,
            store = store,
            navModule = navModule,
            velocityThresholdPx = velocityThresholdPx,
            isLtr = { latestLayoutDirection.value == LayoutDirection.Ltr },
            stateProvider = { latestState.value }
        )
    }
    return this
        .onSizeChanged {
            connection.containerWidthPx = it.width.toFloat()
            connection.containerHeightPx = it.height.toFloat()
        }
        .nestedScroll(connection)
}

internal class GestureNestedScrollConnection(
    private val controller: InteractiveTransitionController,
    private val store: Store,
    private val navModule: NavigationModule,
    private val velocityThresholdPx: Float,
    private val isLtr: () -> Boolean,
    private val stateProvider: () -> NavigationState
) : NestedScrollConnection {

    private enum class ScrubAxis { Vertical, Horizontal }

    var containerWidthPx: Float = 0f
    var containerHeightPx: Float = 0f

    private var scrubbing = false
    private var scrubAxis = ScrubAxis.Vertical
    private var scrubTop: NavigationEntry? = null
    private var scrubRevealed: NavigationEntry? = null
    private var scrubModal = false

    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        if (!scrubbing || source != NestedScrollSource.UserInput) return Offset.Zero
        return when (scrubAxis) {
            ScrubAxis.Vertical -> {
                if (available.y >= 0f) return Offset.Zero
                val height = containerHeightPx.takeIf { it > 0f } ?: return Offset.Zero
                val current = controller.progress
                val target = (current + available.y / height).coerceAtLeast(0f)
                controller.scrubTo(target)
                Offset(0f, (target - current) * height)
            }

            ScrubAxis.Horizontal -> {
                val width = containerWidthPx.takeIf { it > 0f } ?: return Offset.Zero
                val backDelta = if (isLtr()) available.x else -available.x
                if (backDelta >= 0f) return Offset.Zero
                val current = controller.progress
                val target = (current + backDelta / width).coerceAtLeast(0f)
                controller.scrubTo(target)
                val consumedBack = (target - current) * width
                Offset(if (isLtr()) consumedBack else -consumedBack, 0f)
            }
        }
    }

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        if (source != NestedScrollSource.UserInput) return Offset.Zero
        if (scrubbing) {
            return when (scrubAxis) {
                ScrubAxis.Vertical -> {
                    if (available.y <= 0f) return Offset.Zero
                    val height = containerHeightPx.takeIf { it > 0f } ?: return Offset.Zero
                    controller.scrubTo(controller.progress + available.y / height)
                    Offset(0f, available.y)
                }

                ScrubAxis.Horizontal -> {
                    val width = containerWidthPx.takeIf { it > 0f } ?: return Offset.Zero
                    val backDelta = if (isLtr()) available.x else -available.x
                    if (backDelta <= 0f) return Offset.Zero
                    controller.scrubTo(controller.progress + backDelta / width)
                    Offset(available.x, 0f)
                }
            }
        }

        val state = stateProvider()
        if (controller.contentTransitionActive) return Offset.Zero

        if (available.y > 0f && containerHeightPx > 0f) {
            val kind = dismissKindFor(state) ?: return Offset.Zero
            if (!controller.beginScrub(kind)) return Offset.Zero
            scrubbing = true
            scrubAxis = ScrubAxis.Vertical
            when (kind) {
                is InteractiveTransitionController.ScrubKind.ContentDismiss -> {
                    scrubTop = kind.topEntry
                    scrubRevealed = kind.revealedEntry
                    scrubModal = false
                }

                is InteractiveTransitionController.ScrubKind.ModalDismiss -> {
                    scrubTop = kind.modalEntry
                    scrubRevealed = null
                    scrubModal = true
                }

                else -> Unit
            }
            controller.scrubTo(available.y / containerHeightPx)
            return Offset(0f, available.y)
        }

        val backDelta = if (isLtr()) available.x else -available.x
        if (backDelta > 0f && containerWidthPx > 0f) {
            if (!canArmInteractiveBackGesture(state, navModule)) return Offset.Zero
            val top = state.currentEntry
            val revealed = revealedEntryForBack(state) ?: return Offset.Zero
            val kind = InteractiveTransitionController.ScrubKind.ContentBack(top, revealed)
            if (!controller.beginScrub(kind)) return Offset.Zero
            scrubbing = true
            scrubAxis = ScrubAxis.Horizontal
            scrubTop = top
            scrubRevealed = revealed
            scrubModal = false
            controller.scrubTo(backDelta / containerWidthPx)
            return Offset(available.x, 0f)
        }

        return Offset.Zero
    }

    override suspend fun onPreFling(available: Velocity): Velocity {
        if (!scrubbing) return Velocity.Zero
        scrubbing = false
        val top = scrubTop
        val revealed = scrubRevealed
        val modal = scrubModal
        val axis = scrubAxis
        scrubTop = null
        scrubRevealed = null
        scrubModal = false
        val extent = when (axis) {
            ScrubAxis.Vertical -> containerHeightPx
            ScrubAxis.Horizontal -> containerWidthPx
        }.takeIf { it > 0f }
        if (top == null || extent == null) {
            controller.reset()
            return available
        }
        val axisVelocity = when (axis) {
            ScrubAxis.Vertical -> available.y
            ScrubAxis.Horizontal -> if (isLtr()) available.x else -available.x
        }
        val commit = InteractiveTransitionController.shouldCommit(
            progress = controller.progress,
            velocity = axisVelocity,
            velocityThreshold = velocityThresholdPx
        )
        val progressVelocity = axisVelocity / extent
        if (modal) {
            completeModalDismiss(commit, progressVelocity, controller, store, navModule, top)
        } else if (revealed != null) {
            completeContentGesture(commit, progressVelocity, controller, store, navModule, top, revealed)
        } else {
            controller.reset()
        }
        return available
    }

    private fun dismissKindFor(state: NavigationState): InteractiveTransitionController.ScrubKind? {
        val navigatable = navModule.resolveNavigatable(state.currentEntry) ?: return null
        if (navigatable is Modal) {
            if (!navigatable.swipeToDismiss) return null
            if (!canHandleBack(state, navModule)) return null
            return InteractiveTransitionController.ScrubKind.ModalDismiss(state.currentEntry)
        }
        if (!canArmSwipeDismiss(state, navModule)) return null
        val revealed = revealedEntryForBack(state) ?: return null
        return InteractiveTransitionController.ScrubKind.ContentDismiss(state.currentEntry, revealed)
    }
}

internal suspend fun completeContentGesture(
    commit: Boolean,
    progressVelocity: Float,
    controller: InteractiveTransitionController,
    store: Store,
    navModule: NavigationModule,
    top: NavigationEntry,
    revealed: NavigationEntry
) {
    try {
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
        controller.armHandoff(poppedKey = top.stableKey, targetKey = revealed.stableKey)
        val dismissHandler = navModule.resolveNavigatable(top)?.onDismissRequest
        if (dismissHandler != null) {
            dismissHandler.invoke(store)
        } else {
            store.navigateBack()
        }
        val after = store.selectState<NavigationState>().first()
        if (after.currentEntry.stableKey == top.stableKey) {
            controller.settle(commit = false)
        }
    } finally {
        controller.reset()
    }
}
