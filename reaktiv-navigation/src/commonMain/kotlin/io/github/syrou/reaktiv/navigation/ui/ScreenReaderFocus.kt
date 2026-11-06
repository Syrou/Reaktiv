package io.github.syrou.reaktiv.navigation.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf

internal interface ScreenReaderFocusTarget

internal interface ScreenReaderFocus {
    fun focusedNode(): ScreenReaderFocusTarget?
    fun moveTo(node: ScreenReaderFocusTarget)
}

internal val LocalScreenReaderFocus = staticCompositionLocalOf<ScreenReaderFocus?> { null }

@Composable
internal expect fun rememberScreenReaderFocus(): ScreenReaderFocus?
