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
 */
data class LayerAnimationState(
    val currentEntry: NavigationEntry,
    val previousEntry: NavigationEntry?,
    val animationDecision: AnimationDecision?,
    val aliveEntries: List<NavigationEntry>
) {
    val hasAnimation: Boolean = previousEntry != null && animationDecision != null
}

/**
 * Manages animation state for content layer screen transitions
 *
 * Strategy:
 * - Forward navigation (A → B): Keep both A and B composed; A preserved for back navigation
 * - Back navigation (B → A): Keep both during animation, dispose B after completion
 * - Multi-step forward (A → B → C): Dispose A when navigating to C (not in immediate backstack)
 *
 * This preserves LaunchedEffect and composition state without movableContentOf by keeping
 * the previous screen composed in the background during forward navigation.
 *
 * @param entries The content layer entries (typically just the current entry)
 * @return Animation state containing current, previous, and all entries to keep composed
 */
@Composable
fun rememberLayerAnimationState(
    entries: List<NavigationEntry>
): LayerAnimationState {
    val navState by composeState<NavigationState>()
    val currentEntry = entries.lastOrNull() ?: error("Layer must have at least one entry")

    // Track previous entry for animation detection
    val previousEntryState = remember { mutableStateOf<NavigationEntry?>(null) }
    val currentEntryState = remember { mutableStateOf(currentEntry) }

    // Update entries when current changes
    if (currentEntryState.value.stableKey != currentEntry.stableKey) {
        previousEntryState.value = currentEntryState.value
        currentEntryState.value = currentEntry
    }

    // Determine animation decision
    val animationDecision = if (previousEntryState.value != null) {
        determineContentAnimationDecision(previousEntryState.value!!, currentEntry)
    } else {
        null
    }

    // Check if this is back navigation
    val isBackNavigation = previousEntryState.value?.let { prev ->
        currentEntry.stackPosition < prev.stackPosition
    } ?: false

    // Dispose previous entry after animation completes on back navigation
    LaunchedEffect(currentEntry.stableKey) {
        if (isBackNavigation && animationDecision != null && previousEntryState.value != null) {
            val duration = animationDecision.enterTransition.durationMillis +
                          animationDecision.exitTransition.durationMillis
            if (duration > 0) {
                delay(duration.toLong() + navState.screenRetentionDuration.inWholeMilliseconds)
            }
            // Check if previous entry is still not in backstack, then dispose
            val prevKey = previousEntryState.value?.stableKey
            val isInBackstack = navState.backStack.any { it.stableKey == prevKey }
            if (!isInBackstack) {
                previousEntryState.value = null
            }
        }
    }

    // Keep only current and previous entries composed
    // This gives us smooth animations while preserving state for back navigation
    val entriesToRender = buildList {
        add(currentEntry)
        previousEntryState.value?.let { prev ->
            // Only add if it's different from current
            if (prev.stableKey != currentEntry.stableKey) {
                add(prev)
            }
        }
    }

    return LayerAnimationState(
        currentEntry = currentEntry,
        previousEntry = previousEntryState.value,
        animationDecision = animationDecision,
        aliveEntries = entriesToRender
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

    // Update entry states when entries change
    if (previousEntries.value != currentEntryIds) {
        val newStates = entryStates.value.toMutableMap()

        // Add new entries
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

        // Mark removed entries as exiting
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
        // Remove from state
        isExiting -> null
        else -> this
    }
}

