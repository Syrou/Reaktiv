package io.github.syrou.reaktiv.navigation.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import io.github.syrou.reaktiv.compose.composeState
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
data class LayerAnimationState(
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
 * - Forward navigation (A → B): Keep both A and B composed; A preserved for back navigation
 * - Back navigation (B → A): Keep both during animation, dispose B after completion
 *
 * Previous entry is tracked locally in Compose and cleared after animation duration.
 * This avoids storing animation state in NavigationState.
 *
 * @param entries The content layer entries (typically just the current entry)
 * @return Animation state containing current, previous, and all entries to keep composed
 */
@Composable
fun rememberLayerAnimationState(
    entries: List<NavigationEntry>
): LayerAnimationState {
    val currentEntry = entries.lastOrNull() ?: error("Layer must have at least one entry")

    val previousEntryState = remember { mutableStateOf<NavigationEntry?>(null) }
    val currentEntryState = remember { mutableStateOf(currentEntry) }

    if (currentEntryState.value.stableKey != currentEntry.stableKey) {
        previousEntryState.value = currentEntryState.value
        currentEntryState.value = currentEntry
    }

    val previousEntry = previousEntryState.value

    val animationDecision = previousEntry?.let { prev ->
        determineContentAnimationDecision(prev, currentEntry)
    }

    val isBackNavigation = previousEntry?.let { prev ->
        currentEntry.stackPosition < prev.stackPosition
    } ?: false

    LaunchedEffect(currentEntry.stableKey) {
        if (previousEntry != null && animationDecision != null) {
            val exitDuration = previousEntry.navigatable.exitTransition.durationMillis
            val enterDuration = currentEntry.navigatable.enterTransition.durationMillis
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
fun rememberModalAnimationState(
    entries: List<NavigationEntry>
): List<ModalEntryState> {
    val entryStates = remember { mutableStateOf<Map<String, ModalEntryState>>(emptyMap()) }
    val previousEntries = remember { mutableStateOf<Set<String>>(emptySet()) }

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
            newStates[id]?.let { state ->
                newStates[id] = state.copy(
                    isExiting = true,
                    isEntering = false
                )
            }
        }

        entryStates.value = newStates
        previousEntries.value = currentEntryIds
    }

    return entryStates.value.values
        .sortedBy { it.entry.navigatable.elevation }
}

/**
 * State for individual modal entries
 */
data class ModalEntryState(
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

    fun markCompleted(): ModalEntryState? = when {
        isEntering -> copy(isEntering = false)
        isExiting -> null
        else -> this
    }
}
