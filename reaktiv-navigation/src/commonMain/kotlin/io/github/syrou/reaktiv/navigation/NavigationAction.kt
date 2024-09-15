package io.github.syrou.reaktiv.navigation

import io.github.syrou.reaktiv.core.ModuleAction
import io.github.syrou.reaktiv.core.serialization.StringAnyMap

sealed class NavigationAction : ModuleAction(NavigationModule::class) {
    data class Navigate(
        val route: String,
        val params: Map<String, Any> = emptyMap(),
        val popUpTo: String? = null,
        val inclusive: Boolean = false,
        val replaceWith: String? = null,
        val clearBackStack: Boolean = false,
        val forwardParams: Boolean = false
    ) : NavigationAction()

    data object Back : NavigationAction()
    data class PopUpTo(
        val route: String,
        val inclusive: Boolean,
        val replaceWith: String? = null,
        val replaceParams: Map<String, Any> = emptyMap(),
    ) : NavigationAction()


    data class ClearBackStack(val root: String? = null, val params: StringAnyMap = emptyMap()) : NavigationAction()
    data class Replace(val route: String, val params: Map<String, Any> = emptyMap()) : NavigationAction()
    data class SetLoading(val isLoading: Boolean) : NavigationAction()
    data class UpdateAnimationState(val state: AnimationLifecycleState) : NavigationAction()
    data object ClearCurrentScreenParams : NavigationAction()
    data class ClearCurrentScreenParam(val key: String) : NavigationAction()
    data class ClearScreenParams(val route: String) : NavigationAction()
    data class ClearScreenParam(val route: String, val key: String) : NavigationAction()
}