package io.github.syrou.reaktiv.navigation

import io.github.syrou.reaktiv.core.HighPriorityAction
import io.github.syrou.reaktiv.core.ModuleAction
import io.github.syrou.reaktiv.core.serialization.StringAnyMap
import io.github.syrou.reaktiv.navigation.definition.GuidedFlow
import io.github.syrou.reaktiv.navigation.model.GuidedFlowDefinition
import io.github.syrou.reaktiv.navigation.model.GuidedFlowState
import io.github.syrou.reaktiv.navigation.model.ModalContext
import io.github.syrou.reaktiv.navigation.model.NavigationEntry
import kotlinx.serialization.Serializable


sealed class NavigationAction() : ModuleAction(NavigationModule::class) {

    // Core action for atomic state updates
    @Serializable
    data class BatchUpdate(
        val currentEntry: NavigationEntry? = null,
        val backStack: List<NavigationEntry>? = null
    ) : NavigationAction(), HighPriorityAction

    @Serializable
    data class Back(
        val currentEntry: NavigationEntry? = null,
        val backStack: List<NavigationEntry>? = null
    ) : NavigationAction(), HighPriorityAction

    @Serializable
    data class ClearBackstack(
        val currentEntry: NavigationEntry? = null,
        val backStack: List<NavigationEntry>? = null
    ) : NavigationAction(), HighPriorityAction

    @Serializable
    data class BatchUpdateWithModalContext(
        val currentEntry: NavigationEntry? = null,
        val backStack: List<NavigationEntry>? = null,
        val modalContexts: Map<String, ModalContext> = emptyMap()
    ) : NavigationAction(), HighPriorityAction

    // Parameter management actions
    @Serializable
    data object ClearCurrentScreenParams : NavigationAction(), HighPriorityAction

    @Serializable
    data class ClearCurrentScreenParam(
        val key: String
    ) : NavigationAction(), HighPriorityAction

    @Serializable
    data class ClearScreenParams(
        val route: String
    ) : NavigationAction(), HighPriorityAction

    @Serializable
    data class ClearScreenParam(
        val route: String,
        val key: String
    ) : NavigationAction(), HighPriorityAction

    // GuidedFlow actions
    @Serializable
    data class CreateGuidedFlow(
        val definition: GuidedFlowDefinition
    ) : NavigationAction()

    @Serializable
    data class StartGuidedFlow(
        val guidedFlow: GuidedFlow,
        val params: StringAnyMap = emptyMap()
    ) : NavigationAction()

    @Serializable
    data class NextStep(
        val params: StringAnyMap = emptyMap()
    ) : NavigationAction()

    @Serializable
    data object PreviousStep : NavigationAction()

    // Internal GuidedFlow actions
    @Serializable
    data class UpdateActiveGuidedFlow(
        val flowState: GuidedFlowState
    ) : NavigationAction(), HighPriorityAction

    @Serializable
    data object ClearActiveGuidedFlow : NavigationAction(), HighPriorityAction

    // Legacy actions for compatibility
    @Serializable
    data class UpdateCurrentEntry(
        val entry: NavigationEntry
    ) : NavigationAction(), HighPriorityAction

    @Serializable
    data class UpdateBackStack(
        val backStack: List<NavigationEntry>
    ) : NavigationAction(), HighPriorityAction
}