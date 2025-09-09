package io.github.syrou.reaktiv.navigation.util

import io.github.syrou.reaktiv.core.util.ReaktivDebug
import io.github.syrou.reaktiv.navigation.layer.RenderLayer
import io.github.syrou.reaktiv.navigation.model.NavigationEntry
import io.github.syrou.reaktiv.navigation.transition.NavTransition

/**
 * Represents the decision of what animations should run for a navigation transition
 */
data class AnimationDecision(
    val shouldAnimateEnter: Boolean,
    val shouldAnimateExit: Boolean,
    val isForward: Boolean,
    val enterTransition: NavTransition,
    val exitTransition: NavTransition
)


/**
 * Centralized function to determine what animations should run for a navigation transition
 */
fun determineAnimationDecision(
    previousEntry: NavigationEntry,
    currentEntry: NavigationEntry
): AnimationDecision {
    // Quick exits for no animation cases
    if (previousEntry.stackPosition == 0 && currentEntry.stackPosition == 0) {
        return AnimationDecision(false, false, true, NavTransition.None, NavTransition.None)
    }

    val previousId = "${previousEntry.graphId}/${previousEntry.navigatable.route}@${previousEntry.stackPosition}"
    val currentId = "${currentEntry.graphId}/${currentEntry.navigatable.route}@${currentEntry.stackPosition}"

    if (previousId == currentId) {
        return AnimationDecision(false, false, true, NavTransition.None, NavTransition.None)
    }

    // Determine direction
    val isForward = when {
        currentEntry.stackPosition > previousEntry.stackPosition -> true
        currentEntry.stackPosition < previousEntry.stackPosition -> false
        else -> true // Replace operation
    }

    // Get transitions based on direction
    val enterTransition = when {
        !isForward -> previousEntry.navigatable.popEnterTransition ?: currentEntry.navigatable.enterTransition
        else -> currentEntry.navigatable.enterTransition
    }

    val exitTransition = when {
        isForward -> currentEntry.navigatable.popExitTransition ?: previousEntry.navigatable.exitTransition
        else -> previousEntry.navigatable.exitTransition
    }

    // Determine what should animate independently
    val shouldAnimateEnter = enterTransition != NavTransition.Hold && enterTransition != NavTransition.None
    val shouldAnimateExit = exitTransition != NavTransition.Hold && exitTransition != NavTransition.None

    if (ReaktivDebug.isEnabled) {
        ReaktivDebug.nav("🎯 Animation Decision:")
        ReaktivDebug.nav("  Enter animate: $shouldAnimateEnter ($enterTransition)")
        ReaktivDebug.nav("  Exit animate: $shouldAnimateExit ($exitTransition)")
        ReaktivDebug.nav("  Direction: ${if (isForward) "forward" else "backward"}")
    }

    return AnimationDecision(shouldAnimateEnter, shouldAnimateExit, isForward, enterTransition, exitTransition)
}

fun determineContentAnimationDecision(
    previousEntry: NavigationEntry,
    currentEntry: NavigationEntry
): AnimationDecision {
    if (previousEntry.navigatable.renderLayer != RenderLayer.CONTENT ||
        currentEntry.navigatable.renderLayer != RenderLayer.CONTENT
    ) {
        return AnimationDecision(
            shouldAnimateEnter = false,
            shouldAnimateExit = false,
            isForward = true,
            enterTransition = NavTransition.None,
            exitTransition = NavTransition.None
        )
    }
    return determineAnimationDecision(previousEntry, currentEntry)
}