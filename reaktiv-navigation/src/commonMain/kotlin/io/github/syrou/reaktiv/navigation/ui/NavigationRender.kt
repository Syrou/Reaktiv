package io.github.syrou.reaktiv.navigation.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.syrou.reaktiv.core.serialization.StringAnyMap
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.util.NavigationDebugger

/**
 * Updated NavigationRender that works with simplified NavigationState
 */
@Composable
fun NavigationRender(
    modifier: Modifier,
    enableDebug: Boolean = false,
    screenContent: @Composable (Screen, StringAnyMap, Boolean) -> Unit = { _, _, _ -> }
) {

    if (enableDebug) {
        NavigationDebugger()
    }

    // Use simplified navigation rendering
    SimplifiedNavigationRender(
        modifier = modifier,
        screenContent = screenContent
    )
}