package io.github.syrou.reaktiv.navigation

import io.github.syrou.reaktiv.core.ModuleAction

sealed class NavigationAction : ModuleAction(NavigationModule::class) {
    data class Navigate(
        val route: String,
        val params: Map<String, Any> = emptyMap(),
        val popUpTo: String? = null,
        val inclusive: Boolean = false,
        val replaceWith: String? = null
    ) : NavigationAction()

    data object Back : NavigationAction()
    data class PopUpTo(
        val route: String,
        val inclusive: Boolean,
        val replaceWith: String? = null,
        val replaceParams: Map<String, Any> = emptyMap()
    ) : NavigationAction()


    data object ClearBackStack : NavigationAction()
    data class Replace(val route: String, val params: Map<String, Any> = emptyMap()) : NavigationAction()
    data class SetLoading(val isLoading: Boolean) : NavigationAction()
}