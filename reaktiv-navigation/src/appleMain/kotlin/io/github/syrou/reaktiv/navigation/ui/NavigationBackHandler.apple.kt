package io.github.syrou.reaktiv.navigation.ui

import androidx.compose.runtime.Composable

@Composable
internal actual fun platformEdgeSwipeBackEnabled(): Boolean = true

@Composable
internal actual fun PlatformBackHandler(
    enabled: Boolean,
    coordinator: PlatformBackCoordinator
) {
}
