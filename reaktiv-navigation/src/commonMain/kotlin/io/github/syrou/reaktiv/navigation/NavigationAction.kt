package io.github.syrou.reaktiv.navigation

import io.github.syrou.reaktiv.core.HighPriorityAction
import io.github.syrou.reaktiv.core.ModuleAction
import io.github.syrou.reaktiv.navigation.model.ModalContext
import io.github.syrou.reaktiv.navigation.model.NavigationEntry
import io.github.syrou.reaktiv.navigation.model.PendingNavigation
import kotlinx.serialization.Serializable

@Serializable
sealed class NavigationAction : ModuleAction(NavigationModule::class) {

    @Serializable
    data class Navigate(
        val entry: NavigationEntry,
        val modalContext: ModalContext? = null,
        val dismissModals: Boolean = false
    ) : NavigationAction(), HighPriorityAction

    @Serializable
    data class Replace(val entry: NavigationEntry) : NavigationAction(), HighPriorityAction

    @Serializable
    object Back : NavigationAction(), HighPriorityAction

    @Serializable
    object ClearBackstack : NavigationAction(), HighPriorityAction

    @Serializable
    data class PopUpTo(
        val newCurrentEntry: NavigationEntry,
        val newBackStack: List<NavigationEntry>,
        val newModalContexts: Map<String, ModalContext>
    ) : NavigationAction(), HighPriorityAction

    @Serializable
    data class SetPendingNavigation(
        val pending: PendingNavigation
    ) : NavigationAction(), HighPriorityAction

    @Serializable
    object ClearPendingNavigation : NavigationAction(), HighPriorityAction

    @Serializable
    data class AtomicBatch(val actions: List<NavigationAction>) : NavigationAction(), HighPriorityAction

    @Serializable
    object BootstrapComplete : NavigationAction(), HighPriorityAction

    /**
     * Removes all [io.github.syrou.reaktiv.navigation.definition.LoadingModal] entries from
     * the backStack. Dispatched atomically with the navigation batch that follows guard or
     * entry-definition evaluation, so the loading overlay and the destination appear/disappear
     * in a single state emission with no intermediate flash.
     */
    @Serializable
    object RemoveLoadingModals : NavigationAction(), HighPriorityAction
}
