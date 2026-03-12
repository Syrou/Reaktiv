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

/**
 * Protected invitation modal registered directly inside an `intercept { }` block
 * (not inside a named nested graph).
 *
 * This verifies the `navigatableIntercepts` fix: modals placed directly inside
 * `intercept { }` now inherit the auth guard and are redirected to login when
 * the user is unauthenticated, instead of navigating directly to the modal route.
 *
 * Test scenario:
 * 1. Log out (or clear app data) so the session is unauthenticated
 * 2. Deep-link to `reaktiv://example.com/invitation/team`
 * 3. Observe that the app redirects to login and stores a pending navigation
 * 4. Log in — the pending invitation modal should open automatically
 */
object InvitationModal : Modal {
    override val route = "invitation/{type}"
    override val enterTransition = NavTransition.Fade
    override val exitTransition = NavTransition.FadeOut
    override val titleResource: TitleResource? = null
    override val renderLayer: RenderLayer = RenderLayer.SYSTEM

    @Composable
    override fun Content(params: Params) {
        val store = rememberStore()
        val scope = rememberCoroutineScope()
        val inviteType = params["type"] as? String ?: "unknown"

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
                        text = "Invitation",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Text(
                        text = "You have been invited to join: $inviteType",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Text(
                        text = "This modal is registered directly inside intercept { } and uses RenderLayer.SYSTEM. " +
                                "It is protected by the auth guard via navigatableIntercepts.",
                        fontSize = 12.sp,
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
