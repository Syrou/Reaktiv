package io.github.syrou.reaktiv.navigation.util

import io.github.syrou.reaktiv.core.util.ReaktivDebug
import io.github.syrou.reaktiv.navigation.NavigationModule
import io.github.syrou.reaktiv.navigation.layer.RenderLayer
import io.github.syrou.reaktiv.navigation.model.NavigationEntry
import io.github.syrou.reaktiv.navigation.transition.NavTransition

/**
 * Represents the decision of what animations should run for a navigation transition
 */
public data class AnimationDecision(
    val shouldAnimateEnter: Boolean,
    val shouldAnimateExit: Boolean,
    val isForward: Boolean,
    val enterTransition: NavTransition,
    val exitTransition: NavTransition
)


/**
 * Centralized function to determine what animations should run for a navigation transition
 */
public fun determineAnimationDecision(
    previousEntry: NavigationEntry,
    currentEntry: NavigationEntry,
    navModule: NavigationModule,
    isExplicitBackNavigation: Boolean = false
): AnimationDecision {
    val previousId = "${previousEntry.path}@${previousEntry.stackPosition}"
    val currentId = "${currentEntry.path}@${currentEntry.stackPosition}"

    if (previousId == currentId) {
        return AnimationDecision(false, false, true, NavTransition.None, NavTransition.None)
    }

    val isForward = when {
        isExplicitBackNavigation -> false
        currentEntry.stackPosition > previousEntry.stackPosition -> true
        currentEntry.stackPosition < previousEntry.stackPosition && currentEntry.stackPosition > 0 -> false
        else -> true
    }

    val prevNavigatable = previousEntry.navigatable
    val currNavigatable = currentEntry.navigatable

    val enterTransition = when {
        !isForward -> prevNavigatable.popEnterTransition ?: currNavigatable.enterTransition
        else -> currNavigatable.enterTransition
    }

    val exitTransition = when {
        isForward -> currNavigatable.popExitTransition ?: prevNavigatable.exitTransition
        else -> prevNavigatable.exitTransition
    }

    val shouldAnimateEnter = enterTransition != NavTransition.None
    val shouldAnimateExit = exitTransition != NavTransition.None

    if (ReaktivDebug.isEnabled) {
        ReaktivDebug.nav("🎯 Animation Decision:")
        ReaktivDebug.nav("  Enter animate: $shouldAnimateEnter ($enterTransition)")
        ReaktivDebug.nav("  Exit animate: $shouldAnimateExit ($exitTransition)")
        ReaktivDebug.nav("  Direction: ${if (isForward) "forward" else "backward"}")
    }

    return AnimationDecision(shouldAnimateEnter, shouldAnimateExit, isForward, enterTransition, exitTransition)
}

public fun determineContentAnimationDecision(
    previousEntry: NavigationEntry,
    currentEntry: NavigationEntry,
    navModule: NavigationModule,
    isExplicitBackNavigation: Boolean = false
): AnimationDecision {
    val prevLayer = previousEntry.navigatable.renderLayer
    val currLayer = currentEntry.navigatable.renderLayer
    if (prevLayer != RenderLayer.CONTENT || currLayer != RenderLayer.CONTENT) {
        return AnimationDecision(
            shouldAnimateEnter = false,
            shouldAnimateExit = false,
            isForward = true,
            enterTransition = NavTransition.None,
            exitTransition = NavTransition.None
        )
    }
    return determineAnimationDecision(previousEntry, currentEntry, navModule, isExplicitBackNavigation)
}
