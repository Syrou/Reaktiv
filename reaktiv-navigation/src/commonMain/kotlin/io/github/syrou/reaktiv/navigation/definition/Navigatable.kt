package io.github.syrou.reaktiv.navigation.definition

import androidx.compose.runtime.Composable
import io.github.syrou.reaktiv.navigation.alias.ActionResource
import io.github.syrou.reaktiv.navigation.alias.TitleResource
import io.github.syrou.reaktiv.navigation.transition.NavTransition

interface Navigatable : NavigationNode {
    val titleResource: TitleResource?
    val actionResource: ActionResource?
    val enterTransition: NavTransition
    val exitTransition: NavTransition
    val popEnterTransition: NavTransition?
    val popExitTransition: NavTransition?
    val requiresAuth: Boolean

    @Composable
    fun Content(params: Map<String, Any>)
}