package io.github.syrou.reaktiv.navigation.util

import io.github.syrou.reaktiv.core.util.ReaktivDebug
import io.github.syrou.reaktiv.navigation.layer.RenderLayer
import io.github.syrou.reaktiv.navigation.model.NavigationEntry
import io.github.syrou.reaktiv.navigation.transition.NavTransition

fun shouldAnimate(
    previousEntry: NavigationEntry,
    currentEntry: NavigationEntry
): Boolean {
    // Don't animate the initial screen
    if (previousEntry.stackPosition == 0 && currentEntry.stackPosition == 0) return false

    // Build full identifiers for comparison
    val previousId = "${previousEntry.graphId}/${previousEntry.navigatable.route}@${previousEntry.stackPosition}"
    val currentId = "${currentEntry.graphId}/${currentEntry.navigatable.route}@${currentEntry.stackPosition}"

    // If they're exactly the same, no animation
    if (previousId == currentId) return false

    // Determine navigation direction
    val isForward = when {
        currentEntry.stackPosition > previousEntry.stackPosition -> true
        currentEntry.stackPosition < previousEntry.stackPosition -> false
        else -> {
            // Same stack position but different route/graph - this is a replace operation
            // Treat as forward navigation
            true
        }
    }

    val enterTransition = when {
        !isForward -> previousEntry.navigatable.popEnterTransition ?: currentEntry.navigatable.enterTransition
        else -> currentEntry.navigatable.enterTransition
    }

    val exitTransition = when {
        isForward -> currentEntry.navigatable.popExitTransition ?: previousEntry.navigatable.exitTransition
        else -> previousEntry.navigatable.exitTransition
    }

    // Debug logging
    if (ReaktivDebug.isEnabled) {
        ReaktivDebug.nav("Animation check:")
        ReaktivDebug.nav("  Previous: $previousId")
        ReaktivDebug.nav("  Current: $currentId")
        ReaktivDebug.nav("  Forward: $isForward")
        ReaktivDebug.nav("  Enter: $enterTransition")
        ReaktivDebug.nav("  Exit: $exitTransition")
    }

    return enterTransition != NavTransition.Hold && exitTransition != NavTransition.Hold
}

fun shouldAnimateContentTransition(
    previousEntry: NavigationEntry,
    currentEntry: NavigationEntry
): Boolean {
    // For content layer specifically, ensure both are content entries
    if (previousEntry.navigatable.renderLayer != RenderLayer.CONTENT ||
        currentEntry.navigatable.renderLayer != RenderLayer.CONTENT) {
        return false
    }

    return shouldAnimate(previousEntry, currentEntry)
}