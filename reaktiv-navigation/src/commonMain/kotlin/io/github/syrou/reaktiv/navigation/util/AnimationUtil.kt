package io.github.syrou.reaktiv.navigation.util

import io.github.syrou.reaktiv.core.util.ReaktivDebug
import io.github.syrou.reaktiv.navigation.NavigationModule
import io.github.syrou.reaktiv.navigation.definition.Navigatable
import io.github.syrou.reaktiv.navigation.layer.RenderLayer
import io.github.syrou.reaktiv.navigation.transition.TransitionSpec
import io.github.syrou.reaktiv.navigation.transition.presentsItself
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

    // Whatever crosses a boundary is the surface that moves. Entering a graph that presents itself
    // resolves to the graph, everything else to the screen, and a graph declaring nothing resolves
    // back to the screen anyway. Both the timed path here and the interactive scrub read the same
    // source, so a drag cannot disagree with the animation it continues.
    val enteringSource = presentationSourceFor(previousEntry, currentEntry, currNavigatable, navModule)
    val exitingSource = presentationSourceFor(currentEntry, previousEntry, prevNavigatable, navModule)

    val enterSpec = if (!isForward) popEnterSpec(exitingSource, enteringSource) else null
    val exitSpec = if (isForward) {
        pushExitSpec(enteringSource, exitingSource)
    } else {
        popExitSpec(exitingSource)
    }

    val enterTransition = if (isForward) {
        enteringSource.enterTransition ?: currNavigatable.enterTransition
    } else {
        enterSpec?.transition ?: NavTransition.None
    }
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

/**
 * Whichever node is the surface moving between two entries.
 *
 * The outermost graph crossed between [from] and [to] when that graph presents itself, otherwise
 * [navigatable]. Both are a [TransitionSpec], so callers read the same four values either way and
 * nothing has to be converted. Chains run outermost first, so deep-linking into a nested screen
 * animates the outer surface arriving once rather than each level it passed through.
 */
internal fun presentationSourceFor(
    from: NavigationEntry,
    to: NavigationEntry,
    navigatable: Navigatable,
    navModule: NavigationModule
): TransitionSpec {
    val crossed = to.graphChain().firstOrNull { it !in from.graphChain() }
        ?.let { navModule.getGraphDefinitions()[it]?.declaration }
    return crossed?.takeIf { it.presentsItself } ?: navigatable
}
