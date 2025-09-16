package io.github.syrou.reaktiv.navigation

import io.github.syrou.reaktiv.core.HighPriorityAction
import io.github.syrou.reaktiv.core.ModuleAction
import io.github.syrou.reaktiv.navigation.definition.GuidedFlow
import io.github.syrou.reaktiv.navigation.dsl.NavigationOperation
import io.github.syrou.reaktiv.navigation.model.GuidedFlowDefinition
import io.github.syrou.reaktiv.navigation.model.GuidedFlowState
import io.github.syrou.reaktiv.navigation.model.ModalContext
import io.github.syrou.reaktiv.navigation.model.NavigationEntry
import io.github.syrou.reaktiv.navigation.model.NavigationTransitionState
import io.github.syrou.reaktiv.navigation.model.ClearModificationBehavior
import io.github.syrou.reaktiv.navigation.param.Params
import kotlinx.serialization.Serializable


sealed class NavigationAction() : ModuleAction(NavigationModule::class) {

    @Serializable
    data class BatchUpdate(
        val currentEntry: NavigationEntry? = null,
        val backStack: List<NavigationEntry>? = null,
        val modalContexts: Map<String, ModalContext>? = null,
        val operations: List<NavigationOperation> = emptyList(),
        val activeGuidedFlowState: GuidedFlowState? = null,
        val transitionState: NavigationTransitionState = NavigationTransitionState.IDLE
    ) : NavigationAction(), HighPriorityAction

    @Serializable
    data class Back(
        val currentEntry: NavigationEntry? = null,
        val backStack: List<NavigationEntry>? = null,
        val modalContexts: Map<String, ModalContext>? = null,
        val transitionState: NavigationTransitionState = NavigationTransitionState.IDLE
    ) : NavigationAction(), HighPriorityAction

    @Serializable
    data class ClearBackstack(
        val currentEntry: NavigationEntry? = null,
        val backStack: List<NavigationEntry>? = null,
        val modalContexts: Map<String, ModalContext>? = null,
        val transitionState: NavigationTransitionState = NavigationTransitionState.IDLE
    ) : NavigationAction(), HighPriorityAction

    @Serializable
    data class Navigate(
        val currentEntry: NavigationEntry? = null,
        val backStack: List<NavigationEntry>? = null,
        val modalContexts: Map<String, ModalContext>? = null,
        val transitionState: NavigationTransitionState = NavigationTransitionState.IDLE
    ) : NavigationAction(), HighPriorityAction

    @Serializable
    data class PopUpTo(
        val currentEntry: NavigationEntry? = null,
        val backStack: List<NavigationEntry>? = null,
        val modalContexts: Map<String, ModalContext>? = null,
        val transitionState: NavigationTransitionState = NavigationTransitionState.IDLE
    ) : NavigationAction(), HighPriorityAction

    @Serializable
    data class Replace(
        val currentEntry: NavigationEntry? = null,
        val backStack: List<NavigationEntry>? = null,
        val modalContexts: Map<String, ModalContext>? = null,
        val transitionState: NavigationTransitionState = NavigationTransitionState.IDLE
    ) : NavigationAction(), HighPriorityAction

    @Serializable
    data class UpdateGuidedFlowModifications(
        val flowRoute: String,
        val modifiedDefinition: GuidedFlowDefinition?  // null to remove modifications
    ) : NavigationAction(), HighPriorityAction

    @Serializable
    data object ClearAllGuidedFlowModifications : NavigationAction(), HighPriorityAction

    @Serializable
    data class CompleteGuidedFlow(
        val completedFlowState: GuidedFlowState,
        val clearBehavior: ClearModificationBehavior,
        val flowRoute: String
    ) : NavigationAction(), HighPriorityAction

    @Serializable
    data class UpdateTransitionState(
        val transitionState: NavigationTransitionState
    ) : NavigationAction(), HighPriorityAction

}