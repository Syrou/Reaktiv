package io.github.syrou.reaktiv.navigation.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.awaitVerticalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.gestures.verticalDrag
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
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
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import io.github.syrou.reaktiv.compose.composeState
import io.github.syrou.reaktiv.compose.rememberStore
import io.github.syrou.reaktiv.core.Store
import io.github.syrou.reaktiv.navigation.NavigationModule
import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.definition.Modal
import kotlinx.coroutines.launch
import kotlin.math.abs

private const val EDGE_WIDTH_DP = 20
private const val EDGE_EXCLUSION_DP = 32
private const val TOP_DISMISS_EDGE_DP = 32

internal enum class ScrubPass { Initial, Main }

@Composable
internal fun Modifier.scrubRecognizer(
    controller: InteractiveTransitionController,
    vertical: Boolean,
    pass: ScrubPass,
    key: Any?,
    accepts: PointerInputScope.(down: PointerInputChange) -> Boolean = { true },
    arm: (NavigationState) -> InteractiveTransitionController.ScrubKind?
): Modifier {
    val store = rememberStore()
    val navModule = LocalNavigationModule.current
    val navigationState by composeState<NavigationState>()
    val latestState = rememberUpdatedState(navigationState)
    val latestAccepts = rememberUpdatedState(accepts)
    val latestArm = rememberUpdatedState(arm)
    val layoutDirection = LocalLayoutDirection.current
    val scope = rememberCoroutineScope()
    val downPass = if (pass == ScrubPass.Initial) PointerEventPass.Initial else PointerEventPass.Main

    return this.pointerInput(navModule, layoutDirection, key) {
        val velocityThresholdPx = InteractiveTransitionController.COMMIT_VELOCITY_DP_PER_SEC.dp.toPx()
        val slop = viewConfiguration.touchSlop
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = downPass)
            val extent = if (vertical) size.height.toFloat() else size.width.toFloat()
            if (extent <= 0f) return@awaitEachGesture
            if (!latestAccepts.value(this@pointerInput, down)) return@awaitEachGesture
            val kind = latestArm.value(latestState.value) ?: return@awaitEachGesture
            val axis = if (vertical) {
                ScrubAxis.vertical(down, extent)
            } else {
                ScrubAxis.horizontal(down, extent, layoutDirection == LayoutDirection.Ltr)
            }
            val slopChange = when (pass) {
                ScrubPass.Initial -> awaitInitialPassSlop(down, axis, slop)
                ScrubPass.Main -> awaitMainPassSlop(down, axis)
            } ?: return@awaitEachGesture

            if (!controller.beginScrub(kind)) return@awaitEachGesture
            if (pass == ScrubPass.Initial) slopChange.consume()

            val outcome = trackScrub(controller, latestState, down, slopChange, axis, velocityThresholdPx) { onDrag ->
                when {
                    pass == ScrubPass.Initial -> pumpInitialPassDrag(down, onDrag)
                    vertical -> verticalDrag(down.id, onDrag)
                    else -> horizontalDrag(down.id, onDrag)
                }
            }

            scope.launch {
                completeInteractiveDismiss(outcome.commit, outcome.progressVelocity, controller, store, navModule)
            }
        }
    }
}

private suspend fun AwaitPointerEventScope.awaitInitialPassSlop(
    down: PointerInputChange,
    axis: ScrubAxis,
    slop: Float
): PointerInputChange? {
    while (true) {
        val event = awaitPointerEvent(PointerEventPass.Initial)
        val change = event.changes.firstOrNull { it.id == down.id } ?: return null
        if (!change.pressed) return null
        val delta = change.position - down.position
        val along = axis.along(delta)
        val across = abs(axis.across(delta))
        if (across > slop && across >= abs(along)) return null
        if (-along > slop) return null
        if (along > slop && abs(along) > across) return change
    }
}

private suspend fun AwaitPointerEventScope.awaitMainPassSlop(
    down: PointerInputChange,
    axis: ScrubAxis
): PointerInputChange? = if (axis.isVertical) {
    awaitVerticalTouchSlopOrCancellation(down.id) { change, overSlop ->
        if (axis.towards(overSlop)) change.consume()
    }
} else {
    awaitHorizontalTouchSlopOrCancellation(down.id) { change, overSlop ->
        if (axis.towards(overSlop)) change.consume()
    }
}

private fun BackGesturePolicy.admits(down: PointerInputChange): Boolean =
    down.type != PointerType.Mouse || mouseStartsBack

@Composable
internal fun Modifier.backGestureRecognizer(
    controller: InteractiveTransitionController,
    policy: BackGesturePolicy
): Modifier {
    val navModule = LocalNavigationModule.current
    val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
    return scrubRecognizer(
        controller = controller,
        vertical = false,
        pass = ScrubPass.Initial,
        key = policy,
        accepts = { down ->
            val edgePx = EDGE_WIDTH_DP.dp.toPx()
            val inEdge = if (isLtr) down.position.x <= edgePx else down.position.x >= size.width - edgePx
            policy.admits(down) && inEdge
        }
    ) { state -> armContentBack(state, navModule, controller) }
}

@Composable
internal fun Modifier.fullSurfaceBackGestureRecognizer(
    controller: InteractiveTransitionController,
    policy: BackGesturePolicy
): Modifier {
    val navModule = LocalNavigationModule.current
    val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
    return scrubRecognizer(
        controller = controller,
        vertical = false,
        pass = ScrubPass.Main,
        key = policy,
        accepts = { down ->
            val exclusionPx = EDGE_EXCLUSION_DP.dp.toPx()
            val fromEdge = if (isLtr) down.position.x <= exclusionPx else down.position.x >= size.width - exclusionPx
            policy.admits(down) && !(policy.fullSurfaceSkipsEdge && fromEdge)
        }
    ) { state -> armContentBack(state, navModule, controller) }
}

@Composable
internal fun Modifier.dismissGestureRecognizer(controller: InteractiveTransitionController): Modifier {
    val navModule = LocalNavigationModule.current
    return scrubRecognizer(
        controller = controller,
        vertical = true,
        pass = ScrubPass.Main,
        key = Unit
    ) { state -> armContentDismiss(state, navModule, controller) }
}

@Composable
internal fun Modifier.topEdgeDismissRecognizer(controller: InteractiveTransitionController): Modifier {
    val navModule = LocalNavigationModule.current
    val statusBarInsets = WindowInsets.statusBars
    return scrubRecognizer(
        controller = controller,
        vertical = true,
        pass = ScrubPass.Initial,
        key = Unit,
        accepts = { down ->
            val rootCoordinates = controller.rootCoordinates?.takeIf { it.isAttached }
            val indicatorCoordinates = controller.indicatorCoordinates?.takeIf { it.isAttached }
            val zoneTop: Float
            val zoneBottom: Float
            if (rootCoordinates != null && indicatorCoordinates != null) {
                val zone = rootCoordinates.localBoundingBoxOf(indicatorCoordinates, clipBounds = false)
                zoneTop = zone.top
                zoneBottom = zone.bottom
            } else {
                zoneTop = statusBarInsets.getTop(this).toFloat()
                zoneBottom = zoneTop + TOP_DISMISS_EDGE_DP.dp.toPx()
            }
            down.position.y in zoneTop..zoneBottom
        }
    ) { state -> armContentDismiss(state, navModule, controller) }
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

    var containerWidthPx: Float = 0f
    var containerHeightPx: Float = 0f

    private var axis: ScrubAxis? = null

    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        val axis = axis ?: return Offset.Zero
        if (source != NestedScrollSource.UserInput) return Offset.Zero
        val back = axis.along(available)
        if (back >= 0f) return Offset.Zero
        val current = controller.progress
        val target = (current + back / axis.extent).coerceAtLeast(0f)
        controller.scrubTo(target)
        return axis.offsetOf((target - current) * axis.extent)
    }

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        if (source != NestedScrollSource.UserInput) return Offset.Zero
        val active = axis
        if (active != null) {
            val back = active.along(available)
            if (back <= 0f) return Offset.Zero
            controller.scrubTo(controller.progress + back / active.extent)
            return active.offsetOf(back)
        }

        if (controller.contentTransitionActive) return Offset.Zero
        val state = stateProvider()

        if (available.y > 0f && containerHeightPx > 0f) {
            val kind = dismissKindFor(state) ?: return Offset.Zero
            return arm(kind, ScrubAxis.vertical(containerHeightPx), available)
        }

        val horizontal = ScrubAxis.horizontal(containerWidthPx, isLtr())
        if (horizontal.along(available) > 0f && containerWidthPx > 0f) {
            val kind = armContentBack(state, navModule, controller) ?: return Offset.Zero
            return arm(kind, horizontal, available)
        }

        return Offset.Zero
    }

    private fun arm(kind: InteractiveTransitionController.ScrubKind, axis: ScrubAxis, available: Offset): Offset {
        if (!controller.beginScrub(kind)) return Offset.Zero
        this.axis = axis
        val back = axis.along(available)
        controller.scrubTo(back / axis.extent)
        return axis.offsetOf(back)
    }

    override suspend fun onPreFling(available: Velocity): Velocity {
        val axis = axis ?: return Velocity.Zero
        this.axis = null
        val axisVelocity = axis.along(Offset(available.x, available.y))
        val commit = InteractiveTransitionController.shouldCommit(
            progress = controller.progress,
            velocity = axisVelocity,
            velocityThreshold = velocityThresholdPx
        )
        completeInteractiveDismiss(commit, axis.toProgressVelocity(axisVelocity), controller, store, navModule)
        return available
    }

    private fun dismissKindFor(state: NavigationState): InteractiveTransitionController.ScrubKind? =
        if (state.currentEntry.navigatable is Modal) {
            armModalDismiss(state, state.currentEntry)
        } else {
            armContentDismiss(state, navModule, controller)
        }
}
