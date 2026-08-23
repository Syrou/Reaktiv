package eu.syrou.androidexample.ui.screen.wizard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.syrou.reaktiv.compose.composeState
import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.ui.NavigationBackgroundProvider

internal val WIZARD_STEP_ROUTES = listOf("wizard/details", "wizard/payment", "wizard/confirm")

/**
 * Chrome shared by every wizard step, attached to the wizard graph rather than to a screen.
 *
 * The whole wizard is one Material surface, and the steps render inside it. Navigation paints each
 * screen slot with the colour from [NavigationBackgroundProvider] so screens are never see-through
 * mid-transition, which for a layout that owns its own surface would mean the slot covering it. The
 * layout therefore hands its content region a transparent background, so the surface underneath
 * stays visible while steps slide across it.
 *
 * What to look for when validating:
 * - the whole card, header included, slides up as one unit rather than the header appearing first
 * - moving between steps animates only the step body, over an unbroken surface
 * - dragging down anywhere, header included, dismisses the whole wizard
 */
@Composable
internal fun WizardLayout(content: @Composable () -> Unit) {
    val navigationState by composeState<NavigationState>()
    val currentRoute = navigationState.currentEntry.navigatable.route
    val stepIndex = WIZARD_STEP_ROUTES.indexOfFirst { it.endsWith(currentRoute) }
        .takeIf { it >= 0 } ?: 0

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .testTag("wizard-layout"),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 3.dp
            ) {
                Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
                    Text(
                        text = "Checkout",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Step ${stepIndex + 1} of ${WIZARD_STEP_ROUTES.size}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        WIZARD_STEP_ROUTES.forEachIndexed { index, _ ->
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(4.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(
                                        if (index <= stepIndex) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.outlineVariant
                                    )
                                    .testTag("wizard-progress-$index")
                            )
                        }
                    }
                }
            }
            Box(modifier = Modifier.fillMaxSize()) {
                NavigationBackgroundProvider(Color.Transparent) {
                    content()
                }
            }
        }
    }
}
