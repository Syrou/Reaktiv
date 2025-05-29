package io.github.syrou.reaktiv.navigation.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import io.github.syrou.reaktiv.compose.composeState
import io.github.syrou.reaktiv.core.serialization.StringAnyMap
import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.util.NavigationDebugger

@Composable
fun NavigationRender(
    modifier: Modifier,
    enableDebug: Boolean = false,
    screenContent: @Composable (Screen, StringAnyMap, Boolean) -> Unit = { _, _, _ -> }
) {
    val navigationState by composeState<NavigationState>()

    if (enableDebug) {
        NavigationDebugger()
    }

    println("DEBUG: NavigationRender - isNested: ${navigationState.isNestedNavigation}")

    if (navigationState.isNestedNavigation) {
        HierarchicalNavigationRender(
            modifier = modifier,
            screenContent = screenContent
        )
    } else {
        FlatNavigationRender(
            modifier = modifier,
            navigationState = navigationState,
            screenContent = screenContent
        )
    }
}