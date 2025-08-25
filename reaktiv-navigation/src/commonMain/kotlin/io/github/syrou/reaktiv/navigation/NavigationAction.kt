package io.github.syrou.reaktiv.navigation

import io.github.syrou.reaktiv.core.HighPriorityAction
import io.github.syrou.reaktiv.core.ModuleAction
import io.github.syrou.reaktiv.navigation.definition.GuidedFlow
import io.github.syrou.reaktiv.navigation.dsl.NavigationOperation
import io.github.syrou.reaktiv.navigation.model.FlowModification
import io.github.syrou.reaktiv.navigation.model.GuidedFlowDefinition
import io.github.syrou.reaktiv.navigation.model.GuidedFlowState
import io.github.syrou.reaktiv.navigation.model.ModalContext
import io.github.syrou.reaktiv.navigation.model.NavigationEntry
import io.github.syrou.reaktiv.navigation.param.Params
import kotlinx.serialization.Serializable


sealed class NavigationAction() : ModuleAction(NavigationModule::class) {

    @Serializable
    data class BatchUpdate(
        val currentEntry: NavigationEntry? = null,
        val backStack: List<NavigationEntry>? = null,
        val modalContexts: Map<String, ModalContext>? = null,
        val operations: List<NavigationOperation> = emptyList()
    ) : NavigationAction(), HighPriorityAction

    @Serializable
    data class Back(
        val currentEntry: NavigationEntry? = null,
        val backStack: List<NavigationEntry>? = null,
        val modalContexts: Map<String, ModalContext>? = null
    ) : NavigationAction(), HighPriorityAction

    @Serializable
    data class ClearBackstack(
        val currentEntry: NavigationEntry? = null,
        val backStack: List<NavigationEntry>? = null,
        val modalContexts: Map<String, ModalContext>? = null
    ) : NavigationAction(), HighPriorityAction

    @Serializable
    data class Navigate(
        val currentEntry: NavigationEntry? = null,
        val backStack: List<NavigationEntry>? = null,
        val modalContexts: Map<String, ModalContext>? = null
    ) : NavigationAction(), HighPriorityAction

    @Serializable
    data class PopUpTo(
        val currentEntry: NavigationEntry? = null,
        val backStack: List<NavigationEntry>? = null,
        val modalContexts: Map<String, ModalContext>? = null
    ) : NavigationAction(), HighPriorityAction

    @Serializable
    data class Replace(
        val currentEntry: NavigationEntry? = null,
        val backStack: List<NavigationEntry>? = null,
        val modalContexts: Map<String, ModalContext>? = null
    ) : NavigationAction(), HighPriorityAction

    // GuidedFlow actions
    @Serializable
    data class CreateGuidedFlow(
        val definition: GuidedFlowDefinition
    ) : NavigationAction()

    @Serializable
    data class ModifyGuidedFlow(
        val flowRoute: String,
        val modification: FlowModification
    ) : NavigationAction()

    @Serializable
    data class StartGuidedFlow(
        val guidedFlow: GuidedFlow,
        val params: Params = Params.empty()
    ) : NavigationAction()

    @Serializable
    data class NextStep(
        val params: Params = Params.empty()
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

    @Serializable
    data class ClearModifications(val originalFlow: Map<String, GuidedFlowDefinition>) : NavigationAction(), HighPriorityAction
}