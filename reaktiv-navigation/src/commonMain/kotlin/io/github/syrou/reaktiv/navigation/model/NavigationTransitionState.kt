package io.github.syrou.reaktiv.navigation.model

/**
 * Represents the current state of navigation transitions
 */
enum class NavigationTransitionState {
    /**
     * No navigation transition is currently in progress
     */
    IDLE,
    
    /**
     * A navigation transition is currently animating
     */
    ANIMATING,
    
    /**
     * Navigation is temporarily suspended (e.g., waiting for user input)
     */
    SUSPENDED
}