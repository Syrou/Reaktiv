package io.github.syrou.reaktiv.navigation.definition

import io.github.syrou.reaktiv.core.StoreAccessor
import io.github.syrou.reaktiv.navigation.transition.GestureAxis
import io.github.syrou.reaktiv.navigation.transition.NavTransition
import io.github.syrou.reaktiv.navigation.transition.TransitionSpec
import io.github.syrou.reaktiv.navigation.transition.presentationAxis
import io.github.syrou.reaktiv.navigation.transition.presentsItself

/**
 * A declarable navigation graph, the structural counterpart to [Screen] and [Modal].
 *
 * Implement this on an `object` when a graph is a surface in its own right rather than just a
 * grouping: something presented as a sheet, arriving and leaving as one piece with its own
 * transitions and its own dismissal. Structural graphs need none of this and can keep using the
 * `graph("id") { }` form, which declares no presentation and behaves exactly as before.
 *
 * ```kotlin
 * object WizardGraph : Graph {
 *     override val route = "wizard"
 *     override val enterTransition = NavTransition.SlideUpBottom
 *     override val popExitTransition = NavTransition.SlideOutBottom
 * }
 *
 * graph(WizardGraph) {
 *     start(WizardDetailsScreen)
 *     screens(WizardDetailsScreen, WizardPaymentScreen)
 *     layout { content -> WizardLayout(content) }
 * }
 * ```
 *
 * Transitions are nullable on purpose. Null means the graph has no opinion and the entering
 * screen's own transition is used, which is why every existing graph keeps its current behaviour.
 * [NavTransition.None] is a different statement, meaning the graph arrives with no animation.
 *
 * A navigation uses the transition of the outermost boundary it crosses, so entering this graph
 * animates the graph while moving between screens already inside it animates the screen. That is
 * what lets the steps of a wizard slide sideways inside a sheet that arrived from the bottom.
 *
 * @see Screen
 * @see Modal
 */
public interface Graph : NavigationNode, TransitionSpec {
    override val route: String

    /** Transition when a navigation enters this graph from outside it. */
    override val enterTransition: NavTransition? get() = null

    /** Transition when a navigation leaves this graph. */
    override val exitTransition: NavTransition? get() = null

    /**
     * Whether the whole graph can be dragged away as one surface.
     *
     * Derived from the presentation axis the same way [Navigatable.swipeToDismiss] is, so a graph
     * that arrives from the bottom is draggable and nothing else is. Override to false for a
     * vertically presented flow that must be completed rather than dismissed.
     *
     * Unlike the screen-level property, committing this removes every entry belonging to the graph
     * rather than popping one.
     */
    public val swipeToDismiss: Boolean
        get() = enterTransition?.presentationAxis() == GestureAxis.Vertical

    /**
     * Whether to offer the grab affordance for the drag.
     *
     * Follows [swipeToDismiss] rather than defaulting to true, so a structural graph never promises
     * a handle it cannot honour.
     */
    public val showsDismissIndicator: Boolean get() = swipeToDismiss

    /**
     * Whether destinations inside this graph want a navigation header.
     *
     * Derived from whether the graph presents itself, the same way [swipeToDismiss] is: a graph
     * that arrives as its own surface already carries its own chrome, so a sheet or a wizard opts
     * out without anyone remembering to say so. Structural graphs keep the header.
     */
    public val showsNavigationChrome: Boolean get() = !presentsItself

    /**
     * Invoked instead of popping when the graph is dismissed, letting the surface decide what
     * leaving means. Takes precedence over the current screen's own handler.
     */
    public val onDismissRequest: (suspend StoreAccessor.() -> Unit)? get() = null
}
