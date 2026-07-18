package io.github.syrou.reaktiv.navigation.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import io.github.syrou.reaktiv.compose.composeState
import io.github.syrou.reaktiv.navigation.NavigationAction
import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.model.NavigationEntry
import io.github.syrou.reaktiv.navigation.util.AnimationDecision
import io.github.syrou.reaktiv.navigation.util.determineContentAnimationDecision
import kotlinx.coroutines.delay

/**
 * Animation state for content layer rendering
 *
 * Tracks current and previous entries to enable smooth transitions while preserving state.
 *
 * @property currentEntry The currently active screen
 * @property previousEntry The screen being transitioned away from (if any)
 * @property animationDecision Determines which animations to play
 * @property aliveEntries List of entries to keep composed (max 2: current + previous)
 * @property isBackNavigation Whether this is a back navigation (for disposal logic)
 */
public data class LayerAnimationState(
    val currentEntry: NavigationEntry,
    val previousEntry: NavigationEntry?,
    val animationDecision: AnimationDecision?,
    val aliveEntries: List<NavigationEntry>,
    val isBackNavigation: Boolean
) {
    val hasAnimation: Boolean = previousEntry != null && animationDecision != null
}

/**
 * Manages animation state for content layer screen transitions
 *
 * Strategy:
 * - Forward navigation (A -> B): Keep both A and B composed; A preserved for back navigation
 * - Back navigation (B -> A): Keep both during animation, dispose B after completion
 *
 * Previous entry is tracked locally in Compose and cleared after animation duration.
 * This avoids storing animation state in NavigationState.
 *
 * @param currentEntry The currently active screen entry
 * @return Animation state containing current, previous, and all entries to keep composed
 */
@Composable
public fun rememberLayerAnimationState(
    currentEntry: NavigationEntry
): LayerAnimationState {
    val navModule = LocalNavigationModule.current
    val navigationState by composeState<NavigationState>()
    val isExplicitBackNavigation = navigationState.lastNavigationAction is NavigationAction.Back
    val interactiveController = LocalInteractiveTransitionController.current

    val previousEntryState = remember { mutableStateOf<NavigationEntry?>(null) }
    val currentEntryState = remember { mutableStateOf(currentEntry) }
    val previousRenderWasEvaluating = remember { mutableStateOf(false) }
    val isCurrentlyEvaluating = navigationState.isEvaluatingNavigation

    if (currentEntryState.value.stableKey != currentEntry.stableKey) {
        val gestureHandled = interactiveController?.consumeHandoff(
            oldKey = currentEntryState.value.stableKey,
            newKey = currentEntry.stableKey
        ) == true
        if (!previousRenderWasEvaluating.value && !gestureHandled) {
            previousEntryState.value = currentEntryState.value
        }
        currentEntryState.value = currentEntry
    }
    previousRenderWasEvaluating.value = isCurrentlyEvaluating

    val activeScrubKind = interactiveController?.scrubKind
    val contentScrubActive = interactiveController != null &&
        interactiveController.phase != InteractiveTransitionController.Phase.Idle &&
        (activeScrubKind is InteractiveTransitionController.ScrubKind.ContentBack ||
            activeScrubKind is InteractiveTransitionController.ScrubKind.ContentDismiss)
    if (contentScrubActive) {
        previousEntryState.value = null
    }

    val previousEntry = previousEntryState.value

    if (interactiveController != null) {
        SideEffect {
            interactiveController.contentTransitionActive = previousEntry != null
        }
    }

    val animationDecision = previousEntry?.let { prev ->
        determineContentAnimationDecision(prev, currentEntry, navModule, isExplicitBackNavigation)
    }

    val isBackNavigation = isExplicitBackNavigation && previousEntry != null

    LaunchedEffect(currentEntry.stableKey) {
        if (previousEntry != null && animationDecision != null) {
            val exitDuration = animationDecision.exitTransition.durationMillis
            val enterDuration = animationDecision.enterTransition.durationMillis
            val animationDuration = maxOf(exitDuration, enterDuration).toLong()
            if (animationDuration > 0) {
                delay(animationDuration)
            }
            previousEntryState.value = null
        }
    }

    val entriesToRender = buildList {
        add(currentEntry)
        if (previousEntry != null) {
            add(previousEntry)
        }
    }

    return LayerAnimationState(
        currentEntry = currentEntry,
        previousEntry = previousEntry,
        animationDecision = animationDecision,
        aliveEntries = entriesToRender,
        isBackNavigation = isBackNavigation
    )
}

/**
 * Manages animation state for modal overlays
 *
 * Modals have different lifecycle requirements than screens:
 * - Can have multiple modals stacked simultaneously
 * - Each modal animates independently (enter/exit)
 * - No backstack preservation needed since modals are ephemeral
 *
 * @param entries The list of currently active modal entries
 * @return List of modal states with enter/exit animation tracking
 */
@Composable
public fun rememberModalAnimationState(
    entries: List<NavigationEntry>
): List<ModalEntryState> {
    val entryStates = remember { mutableStateOf<Map<String, ModalEntryState>>(emptyMap()) }
    val previousEntries = remember { mutableStateOf<Set<String>>(emptySet()) }
    val interactiveController = LocalInteractiveTransitionController.current

    val currentEntryIds = entries.map { it.stableKey }.toSet()

    if (previousEntries.value != currentEntryIds) {
        val newStates = entryStates.value.toMutableMap()

        val added = currentEntryIds - previousEntries.value
        added.forEach { id ->
            val entry = entries.find { it.stableKey == id }
            entry?.let {
                newStates[id] = ModalEntryState(
                    entry = it,
                    isEntering = true,
                    isExiting = false
                )
            }
        }

        val removed = previousEntries.value - currentEntryIds
        removed.forEach { id ->
            if (interactiveController?.consumeModalHandoff(id) == true) {
                newStates.remove(id)
            } else {
                newStates[id]?.let { state ->
                    newStates[id] = state.copy(
                        isExiting = true,
                        isEntering = false
                    )
                }
            }
        }

        entryStates.value = newStates
        previousEntries.value = currentEntryIds
    }

    val navModule = LocalNavigationModule.current
    return entryStates.value.values
        .sortedBy { it.entry.navigatable.elevation }
}

/**
 * State for individual modal entries
 */
public data class ModalEntryState(
    val entry: NavigationEntry,
    val isEntering: Boolean,
    val isExiting: Boolean
) {
    val animationType: NavigationAnimations.AnimationType
        get() = when {
            isEntering -> NavigationAnimations.AnimationType.MODAL_ENTER
            isExiting -> NavigationAnimations.AnimationType.MODAL_EXIT
            else -> NavigationAnimations.AnimationType.MODAL_ENTER
        }

    public fun markCompleted(): ModalEntryState? = when {
        isEntering -> copy(isEntering = false)
        isExiting -> null
        else -> this
    }
}
