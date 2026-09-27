package io.github.syrou.reaktiv.navigation.util

import io.github.syrou.reaktiv.core.util.ReaktivDebug
import io.github.syrou.reaktiv.navigation.NavigationAction
import io.github.syrou.reaktiv.navigation.NavigationModule
import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.TraverseDirection
import io.github.syrou.reaktiv.navigation.TraversePresentation
import io.github.syrou.reaktiv.navigation.definition.Navigatable
import io.github.syrou.reaktiv.navigation.definition.NavigationGraph
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
) {
    val durationMillis: Int
        get() = maxOf(
            if (shouldAnimateEnter) enterTransition.durationMillis else 0,
            if (shouldAnimateExit) exitTransition.durationMillis else 0
        )
}


internal fun NavigationAction?.impliesBackNavigation(): Boolean = when (this) {
    is NavigationAction.Back -> true
    is NavigationAction.PopUpTo -> entryToReAdd == null
    is NavigationAction.Traverse -> direction == TraverseDirection.Back
    else -> false
}

internal fun NavigationAction?.wasAlreadyPresented(): Boolean = when (this) {
    is NavigationAction.Traverse -> presentation == TraversePresentation.AlreadyPresented
    is NavigationAction.Back -> presentation == TraversePresentation.AlreadyPresented
    is NavigationAction.PopUpTo -> presentation == TraversePresentation.AlreadyPresented
    else -> false
}

internal fun NavigationState.animatesInto(entry: NavigationEntry): Boolean = when {
    lastNavigationAction.wasAlreadyPresented() -> false
    entry.navigatable.renderLayer == RenderLayer.CONTENT -> !isEvaluatingNavigation
    else -> true
}

internal fun List<NavigationAction>.lastStackChange(): NavigationAction? = lastOrNull {
    it is NavigationAction.Navigate || it is NavigationAction.Replace || it is NavigationAction.Back ||
        it is NavigationAction.ClearBackstack || it is NavigationAction.PopUpTo || it is NavigationAction.Traverse
}

/**
 * Centralized function to determine what animations should run for a navigation transition
 */
public fun determineAnimationDecision(
    previousEntry: NavigationEntry,
    currentEntry: NavigationEntry,
    navModule: NavigationModule,
    isExplicitBackNavigation: Boolean = false
): AnimationDecision = determineAnimationDecision(
    previousEntry, currentEntry, navModule.getGraphDefinitions(), isExplicitBackNavigation
)

public fun determineAnimationDecision(
    previousEntry: NavigationEntry,
    currentEntry: NavigationEntry,
    graphDefinitions: Map<String, NavigationGraph>,
    isExplicitBackNavigation: Boolean = false
): AnimationDecision {
    val previousId = "${previousEntry.location}@${previousEntry.stackPosition}"
    val currentId = "${currentEntry.location}@${currentEntry.stackPosition}"

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
    val enteringSource = presentationSourceFor(previousEntry, currentEntry, currNavigatable, graphDefinitions)
    val exitingSource = presentationSourceFor(currentEntry, previousEntry, prevNavigatable, graphDefinitions)

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
): TransitionSpec = presentationSourceFor(from, to, navigatable, navModule.getGraphDefinitions())

internal fun presentationSourceFor(
    from: NavigationEntry,
    to: NavigationEntry,
    navigatable: Navigatable,
    graphDefinitions: Map<String, NavigationGraph>
): TransitionSpec {
    val fromChain = from.graphChain
    val crossed = to.graphChain.firstOrNull { it !in fromChain }
        ?.let { graphDefinitions[it]?.declaration }
    return crossed?.takeIf { it.presentsItself } ?: navigatable
}
