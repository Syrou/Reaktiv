package io.github.syrou.reaktiv.navigation

import io.github.syrou.reaktiv.core.HighPriorityAction
import io.github.syrou.reaktiv.core.ModuleAction
import io.github.syrou.reaktiv.navigation.model.ModalContext
import io.github.syrou.reaktiv.navigation.model.NavigationEntry
import kotlinx.serialization.Serializable


sealed class NavigationAction() : ModuleAction(NavigationModule::class) {
    abstract val bypassSpamProtection: Boolean

    // Core action for atomic state updates
    @Serializable
    data class BatchUpdate(
        val currentEntry: NavigationEntry? = null,
        val backStack: List<NavigationEntry>? = null,
        override val bypassSpamProtection: Boolean = false,
    ) : NavigationAction(), HighPriorityAction

    @Serializable
    data class Back(
        val currentEntry: NavigationEntry? = null,
        val backStack: List<NavigationEntry>? = null,
        override val bypassSpamProtection: Boolean = false,
    ) : NavigationAction(), HighPriorityAction

    @Serializable
    data class ClearBackstack(
        val currentEntry: NavigationEntry? = null,
        val backStack: List<NavigationEntry>? = null,
        override val bypassSpamProtection: Boolean = false,
    ) : NavigationAction(), HighPriorityAction

    @Serializable
    data class BatchUpdateWithModalContext(
        val currentEntry: NavigationEntry? = null,
        val backStack: List<NavigationEntry>? = null,
        val modalContexts: Map<String, ModalContext> = emptyMap(),
        override val bypassSpamProtection: Boolean = false,
    ) : NavigationAction(), HighPriorityAction

    // Parameter management actions
    @Serializable
    data object ClearCurrentScreenParams : NavigationAction(), HighPriorityAction {
        override val bypassSpamProtection: Boolean = false
    }

    @Serializable
    data class ClearCurrentScreenParam(
        val key: String,
        override val bypassSpamProtection: Boolean = false
    ) : NavigationAction(), HighPriorityAction

    @Serializable
    data class ClearScreenParams(
        val route: String,
        override val bypassSpamProtection: Boolean = false
    ) : NavigationAction(), HighPriorityAction

    @Serializable
    data class ClearScreenParam(
        val route: String,
        val key: String,
        override val bypassSpamProtection: Boolean = false
    ) : NavigationAction(), HighPriorityAction

    // Legacy actions for compatibility
    @Serializable
    data class UpdateCurrentEntry(
        val entry: NavigationEntry,
        override val bypassSpamProtection: Boolean = false
    ) : NavigationAction(), HighPriorityAction

    @Serializable
    data class UpdateBackStack(
        val backStack: List<NavigationEntry>,
        override val bypassSpamProtection: Boolean = false
    ) : NavigationAction(), HighPriorityAction
}