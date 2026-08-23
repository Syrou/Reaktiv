package io.github.syrou.reaktiv.navigation.ui

import androidx.compose.runtime.Composable

/**
 * Browsers deliver back through history navigation rather than through a system callback the
 * Compose tree can intercept, so there is nothing to register here. Back is driven by the app,
 * either from UI affordances or from the edge swipe gesture below.
 */
@Composable
internal actual fun PlatformBackHandler(
    enabled: Boolean,
    coordinator: PlatformBackCoordinator
) {
}

@Composable
internal actual fun platformEdgeSwipeBackEnabled(): Boolean = true
