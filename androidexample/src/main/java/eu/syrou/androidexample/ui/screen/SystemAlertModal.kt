package eu.syrou.androidexample.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.syrou.reaktiv.compose.rememberStore
import io.github.syrou.reaktiv.navigation.alias.TitleResource
import io.github.syrou.reaktiv.navigation.definition.Modal
import io.github.syrou.reaktiv.navigation.extension.navigateBack
import io.github.syrou.reaktiv.navigation.layer.RenderLayer
import io.github.syrou.reaktiv.navigation.param.Params
import io.github.syrou.reaktiv.navigation.transition.NavTransition
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

/**
 * Demonstrates a system-layer modal that renders above the loading screen (zIndex 9001f+).
 *
 * Normal modals use [RenderLayer.GLOBAL_OVERLAY] (zIndex ~2000f), which sits below
 * the [AuthLoadingScreen] overlay at zIndex 9000f. By overriding [renderLayer] to
 * [RenderLayer.SYSTEM], this modal is placed at zIndex 9001f and is therefore always
 * visible even while [AuthLoadingScreen] is active.
 *
 * Because [RenderLayer.SYSTEM] navigatables bypass the bootstrap await in NavigationLogic,
 * navigation to this modal executes immediately without waiting for startup to complete.
 * The reducer also preserves SYSTEM entries through [NavigationAction.ClearBackstack] so
 * the modal survives the backstack reset that happens at the end of bootstrap.
 *
 * Test scenario:
 * 1. Start the app (AuthLoadingScreen appears while the guard evaluates with delay)
 * 2. Tap "Show System Alert" on the loading screen
 * 3. Observe that this modal is visible on top of the loading screen
 * 4. Tap "Dismiss" — the loading screen remains until auth completes, then navigates home
 */
@Serializable
object SystemAlertModal : Modal {
    override val route = "system-alert"
    override val enterTransition = NavTransition.Fade
    override val exitTransition = NavTransition.FadeOut
    override val requiresAuth = false
    override val titleResource: TitleResource? = null

    override val renderLayer: RenderLayer
        get() = RenderLayer.SYSTEM

    override val dismissable: Boolean = true

    @Composable
    override fun Content(params: Params) {
        val store = rememberStore()
        val scope = rememberCoroutineScope()

        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Card(
                modifier = Modifier
                    .padding(24.dp)
                    .widthIn(min = 280.dp, max = 400.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = "System Alert",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Text(
                        text = "This modal uses RenderLayer.SYSTEM (zIndex 9001f) and is visible above the loading screen (zIndex 9000f).",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(Modifier.height(8.dp))

                    Button(
                        onClick = {
                            scope.launch { store.navigateBack() }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Dismiss")
                    }
                }
            }
        }
    }
}
