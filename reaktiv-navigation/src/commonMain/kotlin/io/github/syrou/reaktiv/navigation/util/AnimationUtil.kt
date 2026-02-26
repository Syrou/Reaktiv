package io.github.syrou.reaktiv.navigation.util

import io.github.syrou.reaktiv.core.util.ReaktivDebug
import io.github.syrou.reaktiv.navigation.NavigationModule
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
    currentEntry: NavigationEntry,
    navModule: NavigationModule
): AnimationDecision {
    if (previousEntry.stackPosition == 0 && currentEntry.stackPosition == 0) {
        return AnimationDecision(false, false, true, NavTransition.None, NavTransition.None)
    }

    val previousId = "${previousEntry.path}@${previousEntry.stackPosition}"
    val currentId = "${currentEntry.path}@${currentEntry.stackPosition}"

    if (previousId == currentId) {
        return AnimationDecision(false, false, true, NavTransition.None, NavTransition.None)
    }

    val isForward = when {
        currentEntry.stackPosition > previousEntry.stackPosition -> true
        currentEntry.stackPosition < previousEntry.stackPosition -> false
        else -> true
    }

    val prevNavigatable = navModule.resolveNavigatable(previousEntry)
    val currNavigatable = navModule.resolveNavigatable(currentEntry)

    val enterTransition = when {
        !isForward -> prevNavigatable?.popEnterTransition ?: currNavigatable?.enterTransition ?: NavTransition.None
        else -> currNavigatable?.enterTransition ?: NavTransition.None
    }

    val exitTransition = when {
        isForward -> currNavigatable?.popExitTransition ?: prevNavigatable?.exitTransition ?: NavTransition.None
        else -> prevNavigatable?.exitTransition ?: NavTransition.None
    }

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
    currentEntry: NavigationEntry,
    navModule: NavigationModule
): AnimationDecision {
    val prevLayer = navModule.resolveNavigatable(previousEntry)?.renderLayer ?: RenderLayer.CONTENT
    val currLayer = navModule.resolveNavigatable(currentEntry)?.renderLayer ?: RenderLayer.CONTENT
    if (prevLayer != RenderLayer.CONTENT || currLayer != RenderLayer.CONTENT) {
        return AnimationDecision(
            shouldAnimateEnter = false,
            shouldAnimateExit = false,
            isForward = true,
            enterTransition = NavTransition.None,
            exitTransition = NavTransition.None
        )
    }
    return determineAnimationDecision(previousEntry, currentEntry, navModule)
}
