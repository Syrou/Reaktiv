package io.github.syrou.reaktiv.navigation

import io.github.syrou.reaktiv.core.HighPriorityAction
import io.github.syrou.reaktiv.core.ModuleAction
import io.github.syrou.reaktiv.navigation.definition.WindowWidthClass
import io.github.syrou.reaktiv.navigation.model.StartFailure
import io.github.syrou.reaktiv.navigation.model.ModalContext
import io.github.syrou.reaktiv.navigation.model.NavigationEntry
import io.github.syrou.reaktiv.navigation.model.PendingNavigation
import kotlinx.serialization.Contextual
import kotlinx.serialization.SerialName
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
        @Deprecated("Ignored. A modal's underlying screen is derived from the back stack.")
        val modalContext: ModalContext? = null,
        val dismissModals: Boolean = false
    ) : NavigationAction(), HighPriorityAction

    @Serializable
    public data class Replace(@Contextual val entry: NavigationEntry) : NavigationAction(), HighPriorityAction

    @Serializable
    public data class Back(
        val expectedTopKey: String? = null,
        val presentation: TraversePresentation = TraversePresentation.Animate
    ) : NavigationAction(), HighPriorityAction {
        public constructor(expectedTopKey: String?) : this(expectedTopKey, TraversePresentation.Animate)
    }

    @Serializable
    public object ClearBackstack : NavigationAction(), HighPriorityAction

    @Serializable
    public data class PopUpTo(
        val route: String,
        val inclusive: Boolean,
        @Contextual val entryToReAdd: NavigationEntry? = null,
        val targetKey: String? = null,
        val presentation: TraversePresentation = TraversePresentation.Animate
    ) : NavigationAction(), HighPriorityAction {
        public constructor(route: String, inclusive: Boolean, entryToReAdd: NavigationEntry?) :
            this(route, inclusive, entryToReAdd, null)

        public constructor(route: String, inclusive: Boolean, entryToReAdd: NavigationEntry?, targetKey: String?) :
            this(route, inclusive, entryToReAdd, targetKey, TraversePresentation.Animate)
    }

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

    @Serializable
    public data class SetStartFailure(val failure: StartFailure?) : NavigationAction(), HighPriorityAction

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

    @Serializable
    @ConsistentCopyVisibility
    public data class Traverse internal constructor(
        val entries: List<@Contextual NavigationEntry>,
        val direction: TraverseDirection,
        val presentation: TraversePresentation,
        val expectedTopKey: String? = null
    ) : NavigationAction(), HighPriorityAction

    @Serializable
    public data class ScrubUpdate(val scrub: ScrubState) : NavigationAction(), HighPriorityAction

    @Serializable
    public object ScrubEnd : NavigationAction(), HighPriorityAction

    @Serializable
    public data class SetWindowWidthClass(val widthClass: WindowWidthClass) : NavigationAction(), HighPriorityAction
}

@Serializable
public enum class TraverseDirection { Back, Forward }

@Serializable
public enum class TraversePresentation { Animate, AlreadyPresented }

@Serializable
public enum class ScrubType {
    @SerialName("back-scrub")
    Back,

    @SerialName("dismiss-scrub")
    Dismiss,

    @SerialName("modal-dismiss-scrub")
    ModalDismiss
}

private val ScrubType.wireName: String
    get() = ScrubType.serializer().descriptor.getElementName(ordinal)

@Serializable
public data class ScrubState(
    @SerialName("kind") val type: ScrubType,
    val topKey: String,
    val revealedKey: String? = null,
    val progress: Float = 0f
) {
    @Deprecated(
        "Pass a ScrubType instead of its wire name.",
        ReplaceWith("ScrubState(ScrubType.Back, topKey, revealedKey, progress)"),
        level = DeprecationLevel.WARNING
    )
    public constructor(kind: String, topKey: String, revealedKey: String? = null, progress: Float = 0f) : this(
        ScrubType.entries.firstOrNull { it.wireName == kind }
            ?: throw IllegalArgumentException("Unknown scrub kind '$kind'"),
        topKey,
        revealedKey,
        progress
    )

    @Deprecated("Read type instead.", ReplaceWith("type"), level = DeprecationLevel.WARNING)
    val kind: String get() = type.wireName
}
