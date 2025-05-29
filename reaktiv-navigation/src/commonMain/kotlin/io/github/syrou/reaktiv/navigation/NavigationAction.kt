package io.github.syrou.reaktiv.navigation

import io.github.syrou.reaktiv.core.ModuleAction
import io.github.syrou.reaktiv.core.serialization.StringAnyMap
import io.github.syrou.reaktiv.navigation.model.NavigationEntry

sealed class NavigationAction : ModuleAction(NavigationModule::class) {
    // Existing actions - enhanced to work with graphs
    data class Navigate(
        val route: String,
        val params: Map<String, Any> = emptyMap(),
        val popUpTo: String? = null,
        val inclusive: Boolean = false,
        val replaceWith: String? = null,
        val clearBackStack: Boolean = false,
        val forwardParams: Boolean = false,
        // New graph-aware properties (optional for backward compatibility)
        val targetGraphId: String? = null,
    ) : NavigationAction()

    // Existing actions remain unchanged
    data object Back : NavigationAction()

    data class PopUpTo(
        val route: String,
        val inclusive: Boolean,
        val replaceWith: String? = null,
        val replaceParams: Map<String, Any> = emptyMap(),
        val targetGraphId: String? = null
    ) : NavigationAction()

    data class ClearBackStack(
        val root: String? = null,
        val params: StringAnyMap = emptyMap(),
        val targetGraphId: String? = null
    ) : NavigationAction()

    data class Replace(
        val route: String,
        val params: Map<String, Any> = emptyMap()
    ) : NavigationAction()

    data class SetLoading(val isLoading: Boolean) : NavigationAction()

    // Existing param management actions
    data object ClearCurrentScreenParams : NavigationAction()
    data class ClearCurrentScreenParam(val key: String) : NavigationAction()
    data class ClearScreenParams(val route: String) : NavigationAction()
    data class ClearScreenParam(val route: String, val key: String) : NavigationAction()

    data class UpdateCurrentEntry(val entry: NavigationEntry) : NavigationAction()
    data class UpdateBackStack(val backStack: List<NavigationEntry>) : NavigationAction()
    data class UpdateGraphState(val graphId: String, val graphState: GraphState) : NavigationAction()
    data class UpdateActiveGraph(val graphId: String) : NavigationAction()
    data class UpdateGlobalBackStack(val globalBackStack: List<NavigationEntry>) : NavigationAction()
    data class SetClearedBackStackFlag(val cleared: Boolean) : NavigationAction()

    data class BatchUpdate(
        val currentEntry: NavigationEntry? = null,
        val backStack: List<NavigationEntry>? = null,
        val activeGraphId: String? = null,
        val graphStates: Map<String, GraphState>? = null,
        val globalBackStack: List<NavigationEntry>? = null,
        val clearedBackStackWithNavigate: Boolean? = null
    ) : NavigationAction()
}