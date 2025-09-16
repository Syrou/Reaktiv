package io.github.syrou.reaktiv.navigation.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.remember
import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.model.NavigationEntry
import io.github.syrou.reaktiv.navigation.util.AnimationDecision
import io.github.syrou.reaktiv.navigation.util.determineContentAnimationDecision
import kotlinx.coroutines.delay

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
    entries: List<NavigationEntry>,
    navigationState: NavigationState? = null
): LayerAnimationState {
    // Get the active entry for this layer
    val currentEntry = entries.lastOrNull() ?: error("Layer must have at least one entry")
    
    // Content cache to preserve compositions across navigation cycles
    val contentCache = remember { mutableMapOf<String, @Composable () -> Unit>() }
    
    // Track previous entry for animation detection  
    val previousEntryState = remember { mutableStateOf<NavigationEntry?>(null) }
    val currentEntryState = remember { mutableStateOf(currentEntry) }
    
    // Update entries when current changes
    if (currentEntryState.value.stableKey != currentEntry.stableKey) {
        previousEntryState.value = currentEntryState.value
        currentEntryState.value = currentEntry
    }
    
    // Create or retrieve cached content (remember prevents recomposition access)
    val currentMovableContent = remember(currentEntry.stableKey) {
        contentCache.getOrPut(currentEntry.stableKey) {
            movableContentOf {
                currentEntry.navigatable.Content(currentEntry.params)
            }
        }
    }
    
    // Get previous content from cache
    val previousContent = previousEntryState.value?.let { contentCache[it.stableKey] }

    // Memory leak prevention: cleanup cached content for entries no longer reachable
    if (navigationState != null) {
        LaunchedEffect(navigationState.orderedBackStack.size) {
            val reachableKeys = navigationState.orderedBackStack.map { it.stableKey }.toSet()
            val currentKey = currentEntry.stableKey
            val allReachable = reachableKeys + currentKey
            
            // Remove cached content for entries not in backstack or current
            val keysToRemove = contentCache.keys.filter { it !in allReachable }
            keysToRemove.forEach { key ->
                contentCache.remove(key)
            }
        }
    }
    
    // Determine animation decision
    val animationDecision = if (previousEntryState.value != null) {
        determineContentAnimationDecision(previousEntryState.value!!, currentEntry)
    } else {
        null
    }
    
    return LayerAnimationState(
        currentEntry = currentEntry,
        previousEntry = previousEntryState.value,
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
        // Remove from state
        isExiting -> null
        else -> this
    }
}

