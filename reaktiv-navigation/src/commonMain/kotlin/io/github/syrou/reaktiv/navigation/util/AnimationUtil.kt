package io.github.syrou.reaktiv.navigation.util

import io.github.syrou.reaktiv.core.util.ReaktivDebug
import io.github.syrou.reaktiv.navigation.NavigationModule
import io.github.syrou.reaktiv.navigation.layer.RenderLayer
import io.github.syrou.reaktiv.navigation.model.NavigationEntry
import io.github.syrou.reaktiv.navigation.transition.NavTransition
import io.github.syrou.reaktiv.navigation.transition.popEnterSpec
import io.github.syrou.reaktiv.navigation.transition.popExitSpec
import io.github.syrou.reaktiv.navigation.transition.pushExitSpec

/**
 * Represents the decision of what animations should run for a navigation transition
 */
public data class AnimationDecision(
    val shouldAnimateEnter: Boolean,
    val shouldAnimateExit: Boolean,
    val isForward: Boolean,
    val enterTransition: NavTransition,
    val exitTransition: NavTransition,
    val enterReversed: Boolean = false,
    val exitReversed: Boolean = false
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

    val enterSpec = if (!isForward) popEnterSpec(prevNavigatable, currNavigatable) else null
    val exitSpec = if (isForward) {
        pushExitSpec(currNavigatable, prevNavigatable)
    } else {
        popExitSpec(prevNavigatable)
    }

    val enterTransition = if (isForward) currNavigatable.enterTransition else enterSpec?.transition ?: NavTransition.None
    val exitTransition = exitSpec?.transition ?: NavTransition.None
    val enterReversed = enterSpec?.reversedProgress ?: false
    val exitReversed = exitSpec?.reversedProgress ?: false

    val shouldAnimateEnter = enterTransition != NavTransition.None
    val shouldAnimateExit = exitTransition != NavTransition.None

    if (ReaktivDebug.isEnabled) {
        ReaktivDebug.nav("Animation Decision:")
        ReaktivDebug.nav("  Enter animate: $shouldAnimateEnter ($enterTransition, reversed=$enterReversed)")
        ReaktivDebug.nav("  Exit animate: $shouldAnimateExit ($exitTransition, reversed=$exitReversed)")
        ReaktivDebug.nav("  Direction: ${if (isForward) "forward" else "backward"}")
    }

    return AnimationDecision(
        shouldAnimateEnter,
        shouldAnimateExit,
        isForward,
        enterTransition,
        exitTransition,
        enterReversed,
        exitReversed
    )
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
