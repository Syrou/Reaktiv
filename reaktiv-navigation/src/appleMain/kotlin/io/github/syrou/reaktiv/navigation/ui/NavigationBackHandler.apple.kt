package io.github.syrou.reaktiv.navigation.ui

import androidx.compose.runtime.Composable

@Composable
internal actual fun platformBackGesturePolicy(): BackGesturePolicy = BackGesturePolicy.Everywhere

@Composable
internal actual fun PlatformDocumentTitle(title: String?) {
}

@Composable
internal actual fun PlatformBackHandler(
    enabled: Boolean,
    coordinator: PlatformBackCoordinator
) {
}
