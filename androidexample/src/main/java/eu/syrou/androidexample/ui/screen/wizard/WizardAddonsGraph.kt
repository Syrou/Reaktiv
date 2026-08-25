package eu.syrou.androidexample.ui.screen.wizard

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.syrou.reaktiv.compose.rememberStore
import io.github.syrou.reaktiv.navigation.alias.TitleResource
import io.github.syrou.reaktiv.navigation.definition.Graph
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.extension.navigation
import io.github.syrou.reaktiv.navigation.param.Params
import io.github.syrou.reaktiv.navigation.transition.NavTransition

/**
 * A graph nested inside [WizardGraph], for validating that layouts belong to the graph that
 * declares them rather than to the depth you happen to be at.
 *
 * Entering this graph moves deeper into the wizard, so the wizard's own layout is still in scope
 * and must stay on screen. Only the step body should move.
 *
 * Declared with horizontal transitions rather than vertical ones: this is movement inside the
 * wizard, not a new surface arriving over it, which is also why [showsNavigationChrome] resolves
 * the same way it does for the rest of the wizard.
 */
object WizardAddonsGraph : Graph {
    override val route: String = "addons"
    override val enterTransition: NavTransition = NavTransition.SlideInRight
    override val exitTransition: NavTransition = NavTransition.SlideOutLeft
}

/**
 * First screen of the nested graph, and the one that proves the point.
 *
 * The wizard header and progress bar above this are rendered by [WizardLayout], which is attached
 * to the parent graph. Seeing them here means the nested graph did not tear them down.
 */
object WizardAddonsScreen : Screen {
    override val route: String = "list"
    override val titleResource: TitleResource = { "Add-ons" }
    override val enterTransition: NavTransition = NavTransition.SlideInRight
    override val exitTransition: NavTransition = NavTransition.SlideOutLeft

    @Composable
    override fun Content(params: Params) {
        val store = rememberStore()
        WizardStepBody(
            title = "Add-ons",
            description = "This screen lives in a graph nested inside the wizard. The header and " +
                    "progress bar above belong to the wizard graph, so they must still be here and " +
                    "must not have re-animated.",
            tag = "wizard-step-addons",
            forwardLabel = "Pick delivery"
        ) {
            store.navigation { navigateTo("wizard/addons/delivery") }
        }
    }
}

/**
 * Second screen of the nested graph, so the nested case is exercised at more than one level.
 */
object WizardDeliveryScreen : Screen {
    override val route: String = "delivery"
    override val titleResource: TitleResource = { "Delivery" }
    override val enterTransition: NavTransition = NavTransition.SlideInRight
    override val exitTransition: NavTransition = NavTransition.SlideOutLeft

    @Composable
    override fun Content(params: Params) {
        val store = rememberStore()
        WizardStepBody(
            title = "Delivery",
            description = "Two levels deep now. The wizard chrome is still the wizard's, and " +
                    "dragging down should still take the entire wizard away rather than this graph.",
            tag = "wizard-step-delivery",
            forwardLabel = "Back to confirm"
        ) {
            store.navigation { navigateTo("wizard/confirm") }
        }
    }
}
