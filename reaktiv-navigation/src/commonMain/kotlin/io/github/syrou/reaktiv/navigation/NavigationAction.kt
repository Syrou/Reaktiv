package io.github.syrou.reaktiv.navigation

import io.github.syrou.reaktiv.core.HighPriorityAction
import io.github.syrou.reaktiv.core.ModuleAction
import io.github.syrou.reaktiv.navigation.model.ModalContext
import io.github.syrou.reaktiv.navigation.model.NavigationEntry
import io.github.syrou.reaktiv.navigation.model.PendingNavigation
import kotlinx.serialization.Serializable

/**
 * Result of a [NavigationLogic.navigate] call.
 *
 * Callers can ignore the return value for fire-and-forget behaviour (same as before),
 * or inspect it to react to specific outcomes.
 *
 * Example:
 * ```kotlin
 * val outcome = navLogic.navigate { navigateTo(ProfileScreen) }
 * if (outcome is NavigationOutcome.Dropped) {
 *     // another navigation was in progress, retry or notify the user
 * }
 * ```
 */
sealed class NavigationOutcome {
    /** Navigation was executed successfully. */
    data object Success : NavigationOutcome()

    /** Navigation was silently dropped because another navigation was already in progress. */
    data object Dropped : NavigationOutcome()

    /** Navigation was rejected by a guard. */
    data object Rejected : NavigationOutcome()

    /**
     * Navigation was redirected by a guard.
     *
     * @param to The route the guard redirected to.
     */
    data class Redirected(val to: String) : NavigationOutcome()
}

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
        val route: String,
        val inclusive: Boolean,
        val entryToReAdd: NavigationEntry? = null
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

    /**
     * Sets [NavigationState.currentTitle] to the resolved title string for the current entry.
     * Dispatched from [io.github.syrou.reaktiv.navigation.ui.NavigationRender] after invoking
     * the current navigatable's [io.github.syrou.reaktiv.navigation.definition.Navigatable.titleResource]
     * inside the Compose tree, where localization APIs like `stringResource` are available.
     */
    @Serializable
    data class SetCurrentTitle(val title: String?) : NavigationAction()
}
