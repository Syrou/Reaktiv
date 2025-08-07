package io.github.syrou.reaktiv.navigation.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * CompositionLocal for providing default background color to navigation containers
 */
val LocalNavigationBackgroundColor = staticCompositionLocalOf<Color> {
    Color.Unspecified
}

/**
 * Provides a default background color for navigation containers
 */
@Composable
fun NavigationBackgroundProvider(
    backgroundColor: Color = Color.Unspecified,
    content: @Composable () -> Unit
) {
    val effectiveBackgroundColor = when (backgroundColor) {
        Color.Unspecified -> Color.Transparent
        else -> backgroundColor
    }
    
    CompositionLocalProvider(
        LocalNavigationBackgroundColor provides effectiveBackgroundColor,
        content = content
    )
}

/**
 * Gets the current navigation background color from the composition
 */
@Composable
fun rememberNavigationBackgroundColor(): Color {
    return LocalNavigationBackgroundColor.current
}