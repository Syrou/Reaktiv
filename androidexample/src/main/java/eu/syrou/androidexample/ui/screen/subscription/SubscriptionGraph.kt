package eu.syrou.androidexample.ui.screen.subscription

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import eu.syrou.androidexample.reaktiv.subscription.SubscriptionLogic
import eu.syrou.androidexample.reaktiv.subscription.SubscriptionModule
import eu.syrou.androidexample.reaktiv.subscription.SubscriptionPhase
import io.github.syrou.reaktiv.compose.composeState
import io.github.syrou.reaktiv.compose.rememberStore
import io.github.syrou.reaktiv.core.util.selectLogic
import io.github.syrou.reaktiv.navigation.definition.Graph
import io.github.syrou.reaktiv.navigation.transition.NavTransition
import io.github.syrou.reaktiv.navigation.ui.NavigationBackgroundProvider
import kotlinx.coroutines.launch

/**
 * The checkout as a presented surface.
 *
 * Declaring the vertical transitions here is what makes it one surface: it arrives from the bottom
 * with its chrome, its steps cross-fade inside it, dragging down anywhere takes the whole thing
 * away, and the grab handle therefore sits above the chrome this graph draws rather than inside it.
 */
object SubscriptionGraph : Graph {
    override val route: String = "subscribe"
    override val enterTransition: NavTransition = NavTransition.SlideUpBottom
    override val exitTransition: NavTransition = NavTransition.SlideOutBottom
}

/**
 * Chrome shared by every checkout step.
 *
 * The close button leaves the whole flow rather than stepping back through it, which is the same
 * thing the drag on the grab handle above this chrome does.
 */
@Composable
internal fun SubscriptionLayout(content: @Composable () -> Unit) {
    val store = rememberStore()
    val state by composeState<SubscriptionModule.SubscriptionState>()

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .testTag("subscription-layout"),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 3.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Reaktiv Plus",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.testTag("subscription-header")
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = when (state.phase) {
                                SubscriptionPhase.Idle -> "Pick a plan to continue"
                                SubscriptionPhase.Purchasing -> "Taking your money"
                                SubscriptionPhase.Celebrating -> "Welcome aboard"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(
                        onClick = {
                            store.launch { store.selectLogic<SubscriptionLogic>().cancel() }
                        },
                        modifier = Modifier.testTag("subscription-close")
                    ) {
                        Icon(imageVector = Icons.Default.Close, contentDescription = "Close")
                    }
                }
            }
            Box(modifier = Modifier.fillMaxSize()) {
                // The steps cross-fade, so each one has to be opaque or the step being left shows
                // through the one arriving for as long as the fade lasts. Painting them in the
                // colour this layout's own surface uses keeps them indistinguishable from it.
                NavigationBackgroundProvider(MaterialTheme.colorScheme.surfaceVariant) {
                    content()
                }
            }
        }
    }
}
