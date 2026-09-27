package eu.syrou.example.ui.screen

import io.github.syrou.reaktiv.core.util.selectLogic
import io.github.syrou.reaktiv.navigation.NavigationLogic
import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.compose.composeState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.syrou.reaktiv.compose.rememberStore
import io.github.syrou.reaktiv.navigation.definition.LoadingModal
import io.github.syrou.reaktiv.navigation.param.Params
import io.github.syrou.reaktiv.navigation.transition.NavTransition
import kotlinx.coroutines.launch
import io.github.syrou.reaktiv.navigation.extension.navigation
import eu.syrou.example.ui.theme.DarkBackground

object AuthLoadingScreen : LoadingModal {
    override val route = "auth-loading"
    override val enterTransition = NavTransition.Fade
    override val exitTransition = NavTransition.FadeOut

    @Composable
    override fun Content(params: Params) {
        val store = rememberStore()
        val startFailure = composeState<NavigationState>().value.startFailure

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(DarkBackground),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                if (startFailure == null) {
                    CircularProgressIndicator()
                } else {
                    Text(
                        text = "Could not start: ${startFailure.exceptionMessage}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { store.launch { store.selectLogic<NavigationLogic>().retryStart() } }) {
                        Text("Retry")
                    }
                }

                Spacer(Modifier.height(32.dp))

                Button(
                    onClick = {
                        // Use store.launch so the coroutine outlives this composable.
                        // The overlay is removed when activeLoadingScreen clears, which
                        // would cancel a rememberCoroutineScope() before navigate() returns.
                        store.launch {
                            store.navigation { navigateTo<SystemAlertModal>() }
                        }
                    },
                    modifier = Modifier.padding(horizontal = 32.dp)
                ) {
                    Text(
                        text = "Show System Alert",
                        style = MaterialTheme.typography.labelLarge
                    )
                }

                Spacer(Modifier.height(8.dp))

                Text(
                    text = "Tap to test RenderLayer.SYSTEM above loading screen",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
                )
            }
        }
    }
}
