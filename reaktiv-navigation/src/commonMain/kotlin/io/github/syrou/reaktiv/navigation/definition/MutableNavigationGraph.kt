package io.github.syrou.reaktiv.navigation.definition

import androidx.compose.runtime.Composable
import io.github.syrou.reaktiv.navigation.model.EntryDefinition
import io.github.syrou.reaktiv.navigation.model.InterceptDefinition
import kotlinx.serialization.Serializable

@Serializable
data class MutableNavigationGraph(
    override val route: String,
    override val startDestination: StartDestination? = null,
    override val navigatables: List<Navigatable>,
    override val nestedGraphs: List<NavigationGraph>,
    @kotlinx.serialization.Transient
    override val layout: (@Composable (@Composable () -> Unit) -> Unit)? = null,
    @kotlinx.serialization.Transient
    override val interceptDefinition: InterceptDefinition? = null,
    @kotlinx.serialization.Transient
    override val entryDefinition: EntryDefinition? = null
) : NavigationGraph
