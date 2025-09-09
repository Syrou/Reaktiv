package io.github.syrou.reaktiv.navigation.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.remember
import io.github.syrou.reaktiv.navigation.model.NavigationEntry
import io.github.syrou.reaktiv.navigation.util.AnimationDecision
import io.github.syrou.reaktiv.navigation.util.determineContentAnimationDecision

/**
 * Unified animation state for layer rendering
 */
data class LayerAnimationState(
    val currentEntry: NavigationEntry,
    val previousEntry: NavigationEntry?,
    val animationDecision: AnimationDecision?,
    val currentContent: @Composable () -> Unit,
    val previousContent: (@Composable () -> Unit)?
) {
    val hasAnimation: Boolean = previousEntry != null && animationDecision != null
}

/**
 * State management for tracking entries and their movable content across layer renders
 */
@Composable
fun rememberLayerAnimationState(
    entries: List<NavigationEntry>
): LayerAnimationState {
    // Get the active entry for this layer
    val currentEntry = entries.lastOrNull() ?: error("Layer must have at least one entry")
    
    // State tracking for entries and their movable content
    val previousEntryState = remember { mutableStateOf<NavigationEntry?>(null) }
    val previousContentState = remember { mutableStateOf<(@Composable () -> Unit)?>(null) }
    val currentEntryState = remember { mutableStateOf(currentEntry) }
    val currentContentState = remember { mutableStateOf<(@Composable () -> Unit)?>(null) }
    
    // Create movable content for current entry to preserve composition
    val currentMovableContent = remember(currentEntry.stableKey) {
        movableContentOf {
            currentEntry.navigatable.Content(currentEntry.params)
        }
    }
    
    // Update previous entry when current entry changes
    if (currentEntryState.value.stableKey != currentEntry.stableKey) {
        // Store the old current entry and its EXISTING movable content as previous
        previousEntryState.value = currentEntryState.value  
        previousContentState.value = currentContentState.value // Use the existing content, don't recreate
        currentEntryState.value = currentEntry
    }
    
    // Always update current content state
    currentContentState.value = currentMovableContent
    
    val previousEntry = previousEntryState.value
    val previousContent = previousContentState.value
    
    // Determine animation decision
    val animationDecision = if (previousEntry != null) {
        determineContentAnimationDecision(previousEntry, currentEntry)
    } else {
        null
    }
    
    return LayerAnimationState(
        currentEntry = currentEntry,
        previousEntry = previousEntry,
        animationDecision = animationDecision,
        currentContent = currentMovableContent,
        previousContent = previousContent
    )
}

/**
 * Simplified animation state for modal layers that don't need movable content
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
        isExiting -> null // Remove from state
        else -> this
    }
}

