package eu.syrou.androidexample.ui.screen.deeplink

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.syrou.androidexample.reaktiv.auth.AuthModule
import io.github.syrou.reaktiv.compose.composeState
import io.github.syrou.reaktiv.compose.rememberStore
import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.extension.navigation
import io.github.syrou.reaktiv.navigation.param.Params
import io.github.syrou.reaktiv.navigation.transition.NavTransition
import kotlinx.coroutines.launch

object DeeplinkDetailScreen : Screen {
    override val route = "demo-detail"
    override val enterTransition = NavTransition.SlideInRight
    override val exitTransition = NavTransition.SlideOutRight

    @Composable
    override fun Content(params: Params) {
        val store = rememberStore()
        val navState by composeState<NavigationState>()
        val authState by composeState<AuthModule.AuthState>()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(32.dp),
            verticalArrangement = Arrangement.Top,
            horizontalAlignment = Alignment.Start
        ) {
            Text(
                text = "Deeplink Demo — Detail",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Auth: ${if (authState.isAuthenticated == true) "signed in" else "signed out"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(24.dp))
            Text(
                text = "Backstack (${navState.backStack.size} entries)",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(Modifier.height(8.dp))
            navState.backStack.forEachIndexed { index, entry ->
                val label = if (index == 0) "★ resolved start" else "  ↳ synthesized"
                Text(
                    text = "$label  ${entry.route}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (index == 0)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.onBackground.copy(alpha = 0.75f)
                )
                Spacer(Modifier.height(4.dp))
            }
            Spacer(Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))
            Text(
                text = "Params forwarding demo",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Navigate to the deeplink-demo graph with params. The dynamic start lambda resolves to DeeplinkDemoScreen and the params should appear there.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = {
                store.launch {
                    store.navigation {
                        navigateTo("deeplink-demo") {
                            put("source", "detail")
                            put("mode", "params-test")
                        }
                    }
                }
            }) {
                Text("Navigate to graph with params")
            }
        }
    }
}
