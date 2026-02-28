package eu.syrou.androidexample.ui.screen

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
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.unit.dp
import eu.syrou.androidexample.R
import io.github.syrou.reaktiv.compose.rememberStore
import io.github.syrou.reaktiv.navigation.definition.LoadingModal
import io.github.syrou.reaktiv.navigation.extension.navigate
import io.github.syrou.reaktiv.navigation.param.Params
import io.github.syrou.reaktiv.navigation.transition.NavTransition
import kotlinx.coroutines.launch

object AuthLoadingScreen : LoadingModal {
    override val route = "auth-loading"
    override val enterTransition = NavTransition.Fade
    override val exitTransition = NavTransition.FadeOut

    @Composable
    override fun Content(params: Params) {
        val store = rememberStore()

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(colorResource(R.color.dark_background)),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                CircularProgressIndicator()

                Spacer(Modifier.height(32.dp))

                Button(
                    onClick = {
                        // Use store.launch so the coroutine outlives this composable.
                        // The overlay is removed when activeLoadingScreen clears, which
                        // would cancel a rememberCoroutineScope() before navigate() returns.
                        store.launch {
                            store.navigate<SystemAlertModal>()
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
