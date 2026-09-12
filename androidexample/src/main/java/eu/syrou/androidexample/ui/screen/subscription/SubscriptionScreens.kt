package eu.syrou.androidexample.ui.screen.subscription

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import eu.syrou.androidexample.reaktiv.subscription.SubscriptionLogic
import eu.syrou.androidexample.reaktiv.subscription.SubscriptionModule
import eu.syrou.androidexample.reaktiv.subscription.SubscriptionPhase
import eu.syrou.androidexample.reaktiv.subscription.SubscriptionPlan
import io.github.syrou.reaktiv.compose.composeState
import io.github.syrou.reaktiv.compose.rememberStore
import io.github.syrou.reaktiv.core.util.selectLogic
import io.github.syrou.reaktiv.navigation.alias.TitleResource
import io.github.syrou.reaktiv.navigation.definition.DismissAction
import io.github.syrou.reaktiv.navigation.definition.Dismissal
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.extension.navigation
import io.github.syrou.reaktiv.navigation.param.Params
import io.github.syrou.reaktiv.navigation.transition.NavTransition
import kotlinx.coroutines.launch
import kotlin.math.sin

/**
 * First step of the checkout. Steps cross-fade inside the sheet, so the chrome the graph draws stays
 * where it is while the body changes.
 *
 * The pair has to be [NavTransition.Fade] and [NavTransition.FadeOut] rather than Fade twice. Fade
 * is the arriving half, opacity 0 to 1, and the resolution for a pop plays a screen's declared exit
 * backwards to bring it back, so declaring Fade as the exit makes the step being left fade in as the
 * next one arrives and makes a step returned to appear, fade away and snap back.
 */
object SubscriptionPlanScreen : Screen {
    override val route: String = "plan"
    override val titleResource: TitleResource = { "Choose a plan" }
    override val enterTransition: NavTransition = NavTransition.Fade
    override val exitTransition: NavTransition = NavTransition.FadeOut

    @Composable
    override fun Content(params: Params) {
        val store = rememberStore()
        val state by composeState<SubscriptionModule.SubscriptionState>()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp)
                .testTag("subscription-plan"),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SubscriptionPlan.entries.forEach { plan ->
                PlanRow(
                    plan = plan,
                    selected = state.selectedPlan == plan,
                    onSelect = {
                        store.launch { store.selectLogic<SubscriptionLogic>().selectPlan(plan) }
                    }
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = {
                    store.launch { store.navigation { navigateTo("subscribe/payment") } }
                },
                enabled = state.selectedPlan != null,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("subscription-continue")
            ) {
                Text("Continue")
            }
        }
    }
}

@Composable
private fun PlanRow(
    plan: SubscriptionPlan,
    selected: Boolean,
    onSelect: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .testTag("subscription-plan-${plan.name}"),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surface
        },
        border = BorderStroke(
            width = if (selected) 2.dp else 1.dp,
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.outlineVariant
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = plan.label, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = plan.blurb,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = plan.price,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

/**
 * Second step, and the one that gets replaced.
 *
 * The purchase runs on the store rather than on this composition, because this screen is taken out
 * from under it the moment the payment lands.
 */
object SubscriptionPaymentScreen : Screen {
    override val route: String = "payment"
    override val titleResource: TitleResource = { "Payment" }
    override val enterTransition: NavTransition = NavTransition.Fade
    override val exitTransition: NavTransition = NavTransition.FadeOut

    @Composable
    override fun Content(params: Params) {
        val store = rememberStore()
        val state by composeState<SubscriptionModule.SubscriptionState>()
        val plan = state.selectedPlan
        val purchasing = state.phase == SubscriptionPhase.Purchasing

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp)
                .testTag("subscription-payment"),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = plan?.let { "${it.label}, ${it.price}" } ?: "No plan selected",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = "Card ending 4242. Nothing is charged, this is a demo of replacing the " +
                    "current screen once an operation completes.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = {
                    store.launch { store.selectLogic<SubscriptionLogic>().purchase() }
                },
                enabled = plan != null && !purchasing,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("subscription-pay")
            ) {
                if (purchasing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Text("Subscribe")
                }
            }
            OutlinedButton(
                onClick = {
                    store.launch { store.navigation { navigateBack() } }
                },
                enabled = !purchasing,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Back")
            }
        }
    }
}

/**
 * Replaces the payment step rather than stacking on top of it, so there is nothing left to go back
 * to once the purchase has landed.
 *
 * Declared on the root graph rather than inside the checkout, so the sheet and its chrome leave as
 * the celebration arrives and this fills the window, grab strip included: the drag is declined
 * because the flow is over and leaving already happens on its own. Back means the one thing leaving
 * can mean here, finishing the flow and returning to wherever it started, so it runs that rather
 * than popping one entry.
 */
object SubscriptionConfettiScreen : Screen {
    override val route: String = "celebration"
    override val titleResource: TitleResource = { "Subscribed" }
    override val enterTransition: NavTransition = NavTransition.Fade
    override val exitTransition: NavTransition = NavTransition.FadeOut
    override val dismissal: Dismissal = Dismissal(
        back = DismissAction.Run { selectLogic<SubscriptionLogic>().finish() },
        swipe = DismissAction.Ignore
    )

    @Composable
    override fun Content(params: Params) {
        val store = rememberStore()
        val state by composeState<SubscriptionModule.SubscriptionState>()

        Box(
            modifier = Modifier
                .fillMaxSize()
                .testTag("subscription-celebration")
        ) {
            Confetti(modifier = Modifier.fillMaxSize())
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "You are in",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = state.selectedPlan?.let { "${it.label} plan, ${it.price}" }.orEmpty(),
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    text = "This screen replaced the payment step. It takes you back to where the " +
                        "flow was started on its own, or right now if you would rather not wait.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = {
                        store.launch { store.selectLogic<SubscriptionLogic>().finish() }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("subscription-done")
                ) {
                    Text("Done")
                }
            }
        }
    }
}

private val CONFETTI_COLORS = listOf(
    Color(0xFFEF476F),
    Color(0xFFFFD166),
    Color(0xFF06D6A0),
    Color(0xFF118AB2),
    Color(0xFF8338EC)
)

private const val CONFETTI_COUNT = 70

/**
 * Each piece is a pure function of its index, so nothing has to be remembered for the field to hold
 * still across recomposition.
 */
private fun scatter(index: Int, salt: Int): Float {
    val hashed = (index + 1) * 73_856_093 xor (salt + 1) * 19_349_663
    return ((hashed and 0x7fffffff) % 1000).toFloat() / 1000f
}

@Composable
private fun Confetti(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "confetti")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2600, easing = LinearEasing)
        ),
        label = "confetti-fall"
    )

    Canvas(modifier = modifier) {
        repeat(CONFETTI_COUNT) { index ->
            val fall = (progress + scatter(index, 0)) % 1f
            val x = scatter(index, 1) * size.width
            val y = fall * (size.height + 120f) - 60f
            val drift = sin((fall + scatter(index, 2)) * 6.2831855f) * 20f
            val center = Offset(x + drift, y)
            rotate(degrees = fall * 720f + scatter(index, 3) * 360f, pivot = center) {
                drawRoundRect(
                    color = CONFETTI_COLORS[index % CONFETTI_COLORS.size],
                    topLeft = Offset(center.x - 6f, center.y - 11f),
                    size = Size(12f, 22f),
                    cornerRadius = CornerRadius(3f, 3f)
                )
            }
        }
    }
}
