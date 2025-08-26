package io.github.syrou.reaktiv.navigation.model

import kotlinx.serialization.Serializable

/**
 * Defines how guided flow modifications should be cleared when a flow completes.
 */
@Serializable
enum class ClearModificationBehavior {
    /**
     * Clear all flow modifications when this flow completes.
     * This provides a clean slate and is good for temporary flows.
     */
    CLEAR_ALL,
    
    /**
     * Clear only this specific flow's modifications when it completes.
     * Other flows' modifications are preserved.
     */
    CLEAR_SPECIFIC,
    
    /**
     * Don't clear any modifications when this flow completes.
     * All modifications remain for future use.
     */
    CLEAR_NONE
}