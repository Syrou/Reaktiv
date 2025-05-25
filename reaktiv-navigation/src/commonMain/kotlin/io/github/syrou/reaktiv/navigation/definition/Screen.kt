package io.github.syrou.reaktiv.navigation.definition

import androidx.compose.runtime.Composable
import io.github.syrou.reaktiv.navigation.NavTransition
import io.github.syrou.reaktiv.navigation.alias.ActionResource
import io.github.syrou.reaktiv.navigation.alias.TitleResource

interface Screen : NavigationNode {
    val route: String
    val titleResource: TitleResource? get() = null
    val actionResource: ActionResource? get() = null
    val enterTransition: NavTransition
    val exitTransition: NavTransition
    val popEnterTransition: NavTransition? get() = null
    val popExitTransition: NavTransition? get() = null
    val requiresAuth: Boolean

    @Composable
    fun Content(params: Map<String, Any>)
}