package io.github.syrou.reaktiv.navigation

import io.github.syrou.reaktiv.core.HighPriorityAction
import io.github.syrou.reaktiv.core.ModuleAction
import io.github.syrou.reaktiv.core.serialization.StringAnyMap
import io.github.syrou.reaktiv.navigation.model.NavigationEntry

sealed class NavigationAction : ModuleAction(NavigationModule::class) {
    // Core action for atomic state updates
    data class BatchUpdate(
        val currentEntry: NavigationEntry? = null,
        val backStack: List<NavigationEntry>? = null
    ) : NavigationAction(), HighPriorityAction

    data class Back(
        val currentEntry: NavigationEntry? = null,
        val backStack: List<NavigationEntry>? = null
    ) : NavigationAction(), HighPriorityAction

    data class ClearBackstack(
        val currentEntry: NavigationEntry? = null,
        val backStack: List<NavigationEntry>? = null
    ) : NavigationAction(), HighPriorityAction

    // Parameter management actions
    data object ClearCurrentScreenParams : NavigationAction(), HighPriorityAction
    data class ClearCurrentScreenParam(val key: String) : NavigationAction(), HighPriorityAction
    data class ClearScreenParams(val route: String) : NavigationAction(), HighPriorityAction
    data class ClearScreenParam(val route: String, val key: String) : NavigationAction(), HighPriorityAction

    // Legacy actions for compatibility
    data class UpdateCurrentEntry(val entry: NavigationEntry) : NavigationAction(), HighPriorityAction
    data class UpdateBackStack(val backStack: List<NavigationEntry>) : NavigationAction(), HighPriorityAction
}