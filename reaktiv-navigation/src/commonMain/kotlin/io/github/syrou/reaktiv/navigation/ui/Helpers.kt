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