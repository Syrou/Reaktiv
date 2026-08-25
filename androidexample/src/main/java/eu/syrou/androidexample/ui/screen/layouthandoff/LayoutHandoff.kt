package eu.syrou.androidexample.ui.screen.layouthandoff

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.syrou.reaktiv.compose.rememberStore
import io.github.syrou.reaktiv.navigation.alias.TitleResource
import io.github.syrou.reaktiv.navigation.definition.Graph
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.extension.navigateBack
import io.github.syrou.reaktiv.navigation.extension.navigation
import io.github.syrou.reaktiv.navigation.param.Params
import io.github.syrou.reaktiv.navigation.transition.NavTransition
import kotlinx.coroutines.launch

@Composable
private fun HandoffChrome(
    label: String,
    height: Dp,
    color: Color,
    tag: String,
    content: @Composable () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(height)
                .testTag(tag),
            color = color
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Text(
                    text = "${height.value.toInt()}dp tall",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White
                )
            }
        }
        Column(modifier = Modifier.fillMaxSize()) { content() }
    }
}

@Composable
private fun HandoffBody(
    title: String,
    description: String,
    tag: String,
    forwardLabel: String,
    onForward: suspend () -> Unit
) {
    val store = rememberStore()
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp)
            .testTag(tag),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.Start
    ) {
        Text(text = title, style = MaterialTheme.typography.headlineSmall)
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Button(
            onClick = { scope.launch { onForward() } },
            modifier = Modifier.fillMaxWidth()
        ) { Text(forwardLabel) }
        OutlinedButton(
            onClick = { scope.launch { store.navigateBack() } },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Back") }
    }
}

/**
 * Left hand side of the handoff, with the taller header.
 *
 * The two graphs are siblings and declare different layouts, which is the case where the screen
 * being left keeps chrome that the arriving screen does not share.
 */
object HandoffAlphaGraph : Graph {
    override val route: String = "handoff-alpha"
}

/** Right hand side of the handoff, with a shorter header so the difference is obvious. */
object HandoffBetaGraph : Graph {
    override val route: String = "handoff-beta"
}

@Composable
internal fun HandoffAlphaLayout(content: @Composable () -> Unit) {
    HandoffChrome(
        label = "Alpha section",
        height = 96.dp,
        color = MaterialTheme.colorScheme.primary,
        tag = "handoff-alpha-chrome",
        content = content
    )
}

@Composable
internal fun HandoffBetaLayout(content: @Composable () -> Unit) {
    HandoffChrome(
        label = "Beta section",
        height = 56.dp,
        color = MaterialTheme.colorScheme.tertiary,
        tag = "handoff-beta-chrome",
        content = content
    )
}

/**
 * Start of the alpha graph.
 *
 * The exit transition is [NavTransition.None] on purpose. That leaves this screen sitting still and
 * fully visible while the next one slides across it, which is the condition under which an exiting
 * screen used to be rendered without its layout.
 */
object HandoffAlphaScreen : Screen {
    override val route: String = "alpha-overview"
    override val titleResource: TitleResource = { "Alpha" }
    override val enterTransition: NavTransition = NavTransition.SlideInRight
    override val exitTransition: NavTransition = NavTransition.None

    @Composable
    override fun Content(params: Params) {
        val store = rememberStore()
        HandoffBody(
            title = "Alpha overview",
            description = "Tap through and watch the header above. This screen does not animate " +
                    "out, so it stays put while Beta slides across it. Its 96dp header must remain " +
                    "for the whole transition. If it disappears, this text jumps upward.",
            tag = "handoff-alpha-body",
            forwardLabel = "Cross to Beta"
        ) {
            store.navigation { navigateTo("handoff-beta") }
        }
    }
}

/** Start of the beta graph, arriving over alpha. */
object HandoffBetaScreen : Screen {
    override val route: String = "beta-overview"
    override val titleResource: TitleResource = { "Beta" }
    override val enterTransition: NavTransition = NavTransition.SlideInRight
    override val exitTransition: NavTransition = NavTransition.None

    @Composable
    override fun Content(params: Params) {
        val store = rememberStore()
        HandoffBody(
            title = "Beta overview",
            description = "Now cross back. Beta's header is shorter, so the same check applies in " +
                    "reverse: this header must stay put while Alpha slides in over it.",
            tag = "handoff-beta-body",
            forwardLabel = "Cross back to Alpha"
        ) {
            store.navigation { navigateTo("handoff-alpha") }
        }
    }
}
