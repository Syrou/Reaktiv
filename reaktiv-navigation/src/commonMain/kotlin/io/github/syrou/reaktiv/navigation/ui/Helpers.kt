package io.github.syrou.reaktiv.navigation.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import io.github.syrou.reaktiv.compose.composeState
import io.github.syrou.reaktiv.navigation.GraphState
import io.github.syrou.reaktiv.navigation.NavigationState

@Composable
fun rememberActiveGraph(): String {
    val navigationState by composeState<NavigationState>()
    return navigationState.activeGraphId
}

@Composable
fun rememberIsNestedNavigation(): Boolean {
    val navigationState by composeState<NavigationState>()
    return navigationState.isNestedNavigation
}

@Composable
fun rememberCurrentGraphState(): GraphState? {
    val navigationState by composeState<NavigationState>()
    return if (navigationState.isNestedNavigation) {
        navigationState.activeGraphState
    } else {
        null
    }
}

@Composable
fun rememberIsGraphActive(graphId: String): Boolean {
    val navigationState by composeState<NavigationState>()
    return navigationState.isNestedNavigation && navigationState.activeGraphId == graphId
}