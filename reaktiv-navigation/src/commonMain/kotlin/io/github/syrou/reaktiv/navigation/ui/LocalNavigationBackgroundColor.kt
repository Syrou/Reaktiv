package io.github.syrou.reaktiv.navigation.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * CompositionLocal for providing default background color to navigation containers
 */
public val LocalNavigationBackgroundColor: ProvidableCompositionLocal<Color> = staticCompositionLocalOf {
    Color.Unspecified
}

public val LocalDismissIndicatorColor: ProvidableCompositionLocal<Color> = staticCompositionLocalOf {
    Color.Unspecified
}

public val LocalDismissIndicatorBackground: ProvidableCompositionLocal<Color> = staticCompositionLocalOf {
    Color.Unspecified
}

/**
 * Provides a default background color for navigation containers
 */
@Composable
public fun NavigationBackgroundProvider(
    backgroundColor: Color = Color.Unspecified,
    dismissIndicatorColor: Color = Color.Unspecified,
    dismissIndicatorBackground: Color = Color.Unspecified,
    content: @Composable () -> Unit
) {
    val effectiveBackgroundColor = when (backgroundColor) {
        Color.Unspecified -> Color.Transparent
        else -> backgroundColor
    }

    CompositionLocalProvider(
        LocalNavigationBackgroundColor provides effectiveBackgroundColor,
        LocalDismissIndicatorColor provides dismissIndicatorColor,
        LocalDismissIndicatorBackground provides dismissIndicatorBackground,
        content = content
    )
}

/**
 * Gets the current navigation background color from the composition
 */
@Composable
public fun rememberNavigationBackgroundColor(): Color {
    return LocalNavigationBackgroundColor.current
}
