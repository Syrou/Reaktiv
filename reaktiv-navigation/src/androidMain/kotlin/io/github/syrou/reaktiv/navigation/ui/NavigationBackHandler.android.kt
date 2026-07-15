package io.github.syrou.reaktiv.navigation.ui

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

@Composable
internal actual fun platformEdgeSwipeBackEnabled(): Boolean {
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val gestureInsets = WindowInsets.systemGestures
    val systemOwnsEdges = gestureInsets.getLeft(density, layoutDirection) > 0 ||
        gestureInsets.getRight(density, layoutDirection) > 0
    return !systemOwnsEdges
}

@Composable
internal actual fun PlatformBackHandler(
    enabled: Boolean,
    coordinator: PlatformBackCoordinator
) {
    PredictiveBackHandler(enabled = enabled) { events ->
        var scrubbing = false
        try {
            events.collect { event ->
                if (!scrubbing) {
                    scrubbing = coordinator.startScrub()
                }
                if (scrubbing) {
                    coordinator.progress(event.progress)
                }
            }
            withContext(NonCancellable) {
                coordinator.commit()
            }
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                coordinator.cancel()
            }
        }
    }
}
