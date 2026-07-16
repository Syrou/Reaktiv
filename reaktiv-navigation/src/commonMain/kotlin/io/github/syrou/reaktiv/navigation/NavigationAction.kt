package io.github.syrou.reaktiv.navigation

import io.github.syrou.reaktiv.core.HighPriorityAction
import io.github.syrou.reaktiv.core.ModuleAction
import io.github.syrou.reaktiv.navigation.model.ModalContext
import io.github.syrou.reaktiv.navigation.model.NavigationEntry
import io.github.syrou.reaktiv.navigation.model.PendingNavigation
import kotlinx.serialization.Contextual
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
public sealed class NavigationOutcome {
    /** Navigation was executed successfully. */
    public data object Success : NavigationOutcome()

    /** Navigation was silently dropped because another navigation was already in progress. */
    public data object Dropped : NavigationOutcome()

    /** Navigation was rejected by a guard. */
    public data object Rejected : NavigationOutcome()

    /**
     * Navigation was redirected by a guard.
     *
     * @param to The route the guard redirected to.
     */
    public data class Redirected(val to: String) : NavigationOutcome()
}

@Serializable
public sealed class NavigationAction : ModuleAction(NavigationModule::class) {

    @Serializable
    public data class Navigate(
        @Contextual val entry: NavigationEntry,
        val modalContext: ModalContext? = null,
        val dismissModals: Boolean = false
    ) : NavigationAction(), HighPriorityAction

    @Serializable
    public data class Replace(@Contextual val entry: NavigationEntry) : NavigationAction(), HighPriorityAction

    @Serializable
    public object Back : NavigationAction(), HighPriorityAction

    @Serializable
    public object ClearBackstack : NavigationAction(), HighPriorityAction

    @Serializable
    public data class PopUpTo(
        val route: String,
        val inclusive: Boolean,
        @Contextual val entryToReAdd: NavigationEntry? = null
    ) : NavigationAction(), HighPriorityAction

    @Serializable
    public data class SetPendingNavigation(
        val pending: PendingNavigation
    ) : NavigationAction(), HighPriorityAction

    @Serializable
    public object ClearPendingNavigation : NavigationAction(), HighPriorityAction

    @Serializable
    public data class AtomicBatch(val actions: List<NavigationAction>) : NavigationAction(), HighPriorityAction

    @Serializable
    public object BootstrapComplete : NavigationAction(), HighPriorityAction

    /**
     * Sets [NavigationState.isEvaluatingNavigation] to [isEvaluating].
     *
     * Dispatched with `true` when a guard or entry-definition evaluation starts and
     * takes longer than the configured loading threshold. Dispatched with `false` in
     * the finally block of [io.github.syrou.reaktiv.navigation.NavigationLogic] when
     * evaluation completes.
     *
     * @param isEvaluating `true` to show the evaluation overlay; `false` to hide it.
     */
    @Serializable
    public data class SetEvaluating(val isEvaluating: Boolean) : NavigationAction(), HighPriorityAction
}
