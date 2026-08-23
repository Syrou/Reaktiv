package eu.syrou.androidexample.ui.screen.wizard

import io.github.syrou.reaktiv.navigation.definition.Graph
import io.github.syrou.reaktiv.navigation.transition.NavTransition

/**
 * The wizard as a presented surface rather than a grouping.
 *
 * Declaring the transitions here rather than on the first step is what makes the whole thing
 * behave as one surface: it arrives from the bottom with its layout, its steps slide sideways
 * inside it, and dragging down anywhere takes the entire wizard away rather than stepping back
 * through it. `swipeToDismiss` derives from the vertical enter transition, so nothing else has to
 * be declared for the drag.
 */
object WizardGraph : Graph {
    override val route: String = "wizard"
    override val enterTransition: NavTransition = NavTransition.SlideUpBottom
    override val exitTransition: NavTransition = NavTransition.SlideOutBottom
}
