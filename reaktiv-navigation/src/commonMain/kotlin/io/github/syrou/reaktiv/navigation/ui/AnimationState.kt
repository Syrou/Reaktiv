package io.github.syrou.reaktiv.navigation.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import io.github.syrou.reaktiv.compose.composeState
import io.github.syrou.reaktiv.navigation.NavigationAction
import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.dsl.NavigationOperation
import io.github.syrou.reaktiv.navigation.model.NavigationEntry
import io.github.syrou.reaktiv.navigation.util.AnimationDecision
import io.github.syrou.reaktiv.navigation.util.determineContentAnimationDecision
import kotlinx.coroutines.delay

/**
 * Composable that returns the last navigation action for content preservation logic
 */
@Composable
fun rememberLastNavigationAction(): NavigationAction? {
    val navigationState by composeState<NavigationState>()
    return navigationState.lastNavigationAction
}

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
    // Get navigation state for retention duration config
    val navState by composeState<NavigationState>()
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

    // Get the actual navigation intent from last action
    val lastAction = rememberLastNavigationAction()
    val isBackNavigation = lastAction is NavigationAction.Back
    val isClearBackstack = when (lastAction) {
        is NavigationAction.ClearBackstack -> true
        is NavigationAction.BatchUpdate -> lastAction.operations.contains(NavigationOperation.ClearBackStack)
        else -> false
    }
    // Create content: preserve cached content, fresh only for first visit
    val currentMovableContent = if (contentCache.containsKey(currentEntry.stableKey)) {
        // Reuse existing cached content (preserves LaunchedEffect state)
        contentCache[currentEntry.stableKey]!!
    } else {
        // First visit: create fresh content (triggers LaunchedEffect)
        remember(currentEntry.stableKey) {
            movableContentOf {
                currentEntry.navigatable.Content(currentEntry.params)
            }
        }.also { content ->
            contentCache[currentEntry.stableKey] = content
        }
    }

    // Determine animation decision early for cleanup logic
    val animationDecision = if (previousEntryState.value != null) {
        determineContentAnimationDecision(previousEntryState.value!!, currentEntry)
    } else {
        null
    }
    
    // Get previous content from cache
    val previousContent = previousEntryState.value?.let { contentCache[it.stableKey] }

    // Memory management: clean up based on navigation action and animation state
    LaunchedEffect(currentEntry.stableKey, isClearBackstack) {
        val keysToKeep = if (isClearBackstack) {
            // ClearBackstack: only keep current entry since previous is no longer reachable
            setOf(currentEntry.stableKey)
        } else {
            // Normal navigation: keep current and previous for back navigation support
            setOfNotNull(
                currentEntry.stableKey,
                previousEntryState.value?.stableKey
            )
        }
        
        // Remove cached content for entries not needed
        val keysToRemove = contentCache.keys.filter { it !in keysToKeep }
        keysToRemove.forEach { key ->
            contentCache.remove(key)
        }
    }
    
    // Clean up previous content after animation completes for back navigation
    if (isBackNavigation && animationDecision != null) {
        LaunchedEffect(currentEntry.stableKey) {
            // Wait for animation duration + small buffer
            val animationDuration = (animationDecision.enterTransition.durationMillis) +
                                   (animationDecision.exitTransition.durationMillis)
            if (animationDuration > 0) {
                delay(animationDuration.toLong() + navState.screenRetentionDuration.inWholeMilliseconds)
                // Clean up previous content after animation ONLY if it's not the current entry
                // This prevents cleanup while user is on that screen
                previousEntryState.value?.let { prevEntry ->
                    if (prevEntry.stableKey != currentEntry.stableKey) {
                        contentCache.remove(prevEntry.stableKey)
                    }
                }
            }
        }
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

