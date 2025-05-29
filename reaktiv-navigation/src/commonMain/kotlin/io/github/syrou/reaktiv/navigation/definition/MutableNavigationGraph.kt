package io.github.syrou.reaktiv.navigation.definition

import androidx.compose.runtime.Composable

class MutableNavigationGraph(
    override val route: String,
    override val startDestination: StartDestination,
    override val navigatables: List<Navigatable>,
    override val nestedGraphs: List<NavigationGraph>,
    override val layout: (@Composable (@Composable () -> Unit) -> Unit)?
) : NavigationGraph