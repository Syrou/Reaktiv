package eu.syrou.androidexample.ui.screen.wizard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.syrou.reaktiv.compose.rememberStore
import io.github.syrou.reaktiv.navigation.alias.TitleResource
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.extension.navigateBack
import io.github.syrou.reaktiv.navigation.extension.navigation
import io.github.syrou.reaktiv.navigation.param.Params
import io.github.syrou.reaktiv.navigation.transition.NavTransition
import kotlinx.coroutines.launch

/**
 * Body of one wizard step.
 *
 * Only this part should move when stepping between screens: the wizard chrome belongs to the graph
 * and is shared across the steps, so it stays put once the wizard itself has finished arriving.
 */
@Composable
private fun WizardStepBody(
    title: String,
    description: String,
    tag: String,
    forwardLabel: String?,
    onForward: (suspend () -> Unit)?
) {
    val store = rememberStore()
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp)
            .testTag(tag),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(text = title, style = MaterialTheme.typography.headlineSmall)
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (onForward != null && forwardLabel != null) {
            Button(
                onClick = { scope.launch { onForward() } },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(forwardLabel)
            }
        }
        OutlinedButton(
            onClick = { scope.launch { store.navigateBack() } },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Back")
        }
    }
}

/**
 * First wizard step, and the screen the wizard graph starts on.
 *
 * The vertical enter transition is what puts this graph in the case worth validating: the screen
 * underneath stays where it is, so the wizard and its layout have to arrive together.
 */
object WizardDetailsScreen : Screen {
    override val route: String = "details"
    override val titleResource: TitleResource = { "Details" }

    // Only movement within the wizard. How the wizard itself arrives and leaves belongs to
    // WizardGraph, so no step has to describe its own container.
    override val enterTransition: NavTransition = NavTransition.SlideInRight
    override val exitTransition: NavTransition = NavTransition.SlideOutLeft

    @Composable
    override fun Content(params: Params) {
        val store = rememberStore()
        WizardStepBody(
            title = "Your details",
            description = "The card, its header and the progress bar should have slid up together " +
                    "as one surface. Drag down anywhere, including on the header, to dismiss.",
            tag = "wizard-step-details",
            forwardLabel = "Continue to payment"
        ) {
            store.navigation { navigateTo("wizard/payment") }
        }
    }
}

/**
 * Second step, entered from within the wizard graph.
 *
 * Horizontal transitions here so stepping forward reads as movement inside the wizard rather than
 * as a new surface arriving, which also keeps the shared chrome visibly still.
 */
object WizardPaymentScreen : Screen {
    override val route: String = "payment"
    override val titleResource: TitleResource = { "Payment" }
    override val enterTransition: NavTransition = NavTransition.SlideInRight
    override val exitTransition: NavTransition = NavTransition.SlideOutLeft

    @Composable
    override fun Content(params: Params) {
        val store = rememberStore()
        WizardStepBody(
            title = "Payment",
            description = "Only this panel should have moved. The header and progress bar belong " +
                    "to the wizard graph and are shared by every step.",
            tag = "wizard-step-payment",
            forwardLabel = "Review order"
        ) {
            store.navigation { navigateTo("wizard/confirm") }
        }
    }
}

/**
 * Final step. Dismissing from here should take the whole wizard away, chrome included.
 */
object WizardConfirmScreen : Screen {
    override val route: String = "confirm"
    override val titleResource: TitleResource = { "Confirm" }
    override val enterTransition: NavTransition = NavTransition.SlideInRight
    override val exitTransition: NavTransition = NavTransition.SlideOutLeft

    @Composable
    override fun Content(params: Params) {
        WizardStepBody(
            title = "Confirm",
            description = "Step back to the first step to drag the wizard away: vertical " +
                    "dismissal is armed by that screen's vertical enter transition, and it takes the " +
                    "whole wizard with it, chrome included.",
            tag = "wizard-step-confirm",
            forwardLabel = null,
            onForward = null
        )
    }
}
