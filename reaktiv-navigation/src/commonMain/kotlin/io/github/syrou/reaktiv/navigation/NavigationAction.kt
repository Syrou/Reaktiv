package io.github.syrou.reaktiv.navigation

import io.github.syrou.reaktiv.core.ModuleAction
import io.github.syrou.reaktiv.core.serialization.StringAnyMap
import io.github.syrou.reaktiv.navigation.model.NavigationEntry

sealed class NavigationAction : ModuleAction(NavigationModule::class) {
    // Core action for atomic state updates
    data class BatchUpdate(
        val currentEntry: NavigationEntry? = null,
        val backStack: List<NavigationEntry>? = null,
        val isLoading: Boolean? = null
    ) : NavigationAction()

    // Utility actions
    data class SetLoading(val isLoading: Boolean) : NavigationAction()

    // Parameter management actions
    data object ClearCurrentScreenParams : NavigationAction()
    data class ClearCurrentScreenParam(val key: String) : NavigationAction()
    data class ClearScreenParams(val route: String) : NavigationAction()
    data class ClearScreenParam(val route: String, val key: String) : NavigationAction()

    // Legacy actions for compatibility
    data class UpdateCurrentEntry(val entry: NavigationEntry) : NavigationAction()
    data class UpdateBackStack(val backStack: List<NavigationEntry>) : NavigationAction()
}