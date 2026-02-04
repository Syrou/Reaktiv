package io.github.syrou.reaktiv.navigation

import io.github.syrou.reaktiv.core.HighPriorityAction
import io.github.syrou.reaktiv.core.ModuleAction
import io.github.syrou.reaktiv.navigation.dsl.NavigationOperation
import io.github.syrou.reaktiv.navigation.model.GuidedFlowDefinition
import io.github.syrou.reaktiv.navigation.model.GuidedFlowState
import io.github.syrou.reaktiv.navigation.model.ModalContext
import io.github.syrou.reaktiv.navigation.model.NavigationEntry
import kotlinx.serialization.Serializable

@Serializable
sealed class NavigationAction : ModuleAction(NavigationModule::class) {

    @Serializable
    data class BatchUpdate(
        val currentEntry: NavigationEntry? = null,
        val backStack: List<NavigationEntry>? = null,
        val modalContexts: Map<String, ModalContext>? = null,
        val operations: List<NavigationOperation> = emptyList(),
        val activeGuidedFlowState: GuidedFlowState? = null,
        val guidedFlowModifications: Map<String, GuidedFlowDefinition>? = null
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

    /**
     * Clears the previousEntry after animation completes.
     * Dispatched by NavigationLogic after the calculated animation duration.
     */
    @Serializable
    data object ClearPreviousEntry : NavigationAction(), HighPriorityAction

}
