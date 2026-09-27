package eu.syrou.example.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import eu.syrou.example.LocalExamplePlatform
import eu.syrou.example.reaktiv.settings.SettingsLogic
import eu.syrou.example.reaktiv.settings.SettingsModule
import io.github.syrou.reaktiv.compose.composeState
import io.github.syrou.reaktiv.compose.rememberStore
import io.github.syrou.reaktiv.navigation.alias.TitleResource
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.extension.navigation
import io.github.syrou.reaktiv.navigation.param.Params
import io.github.syrou.reaktiv.navigation.transition.NavTransition
import kotlinx.coroutines.launch

object SettingsScreen : Screen {
    override val route: String = "settings"
    override val titleResource: TitleResource = {
        "Settings"
    }
    override val enterTransition: NavTransition = NavTransition.SlideInRight
    override val exitTransition: NavTransition = NavTransition.SlideOutLeft

    @Composable
    override fun Content(
        params: Params
    ) {
        val store = rememberStore()
        val platform = LocalExamplePlatform.current
        val settingsState by composeState<SettingsModule.SettingsState>()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            platform.twitchLogin?.let { twitchLogin ->
                TwitchAuthScreen(
                    isLinked = settingsState.twitchAccessToken != null,
                    onAuthorizeClick = {
                        store.launch {
                            store.navigation {
                                navigateTo(twitchLogin)
                            }
                        }
                    },
                    onUnlinkClick = {
                        store.launch {
                            store.selectLogic<SettingsLogic>().setTwitchAccessToken(null)
                        }
                    }
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
            Button(onClick = {
                store.launch {
                    store.navigation { navigateTo(UserManagementScreens.ViewUser, "id" to 31) }
                }
            }) {
                Text("Start User Management Flow")
            }
        }
    }

    @Composable
    fun TwitchAuthScreen(
        isLinked: Boolean,
        onAuthorizeClick: () -> Unit,
        onUnlinkClick: () -> Unit
    ) {
        var showDialog by remember { mutableStateOf(false) }

        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = if (isLinked) "Twitch Account Linked" else "Link Your Twitch Account",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = if (isLinked)
                        "Your Twitch account is currently linked to Poego."
                    else
                        "Connect your Twitch account to enable advanced features.",
                    style = MaterialTheme.typography.bodyMedium
                )

                Spacer(modifier = Modifier.height(8.dp))

                if (isLinked) {
                    Button(
                        onClick = { showDialog = true },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("Unlink Account")
                    }
                } else {
                    Button(onClick = onAuthorizeClick) {
                        Text("Authorize Twitch API")
                    }
                }
            }
        }
        if (showDialog) {
            AlertDialog(
                onDismissRequest = { showDialog = false },
                title = { Text("Unlink Account") },
                text = { Text("Are you sure you want to unlink your Twitch account?") },
                confirmButton = {
                    Button(
                        onClick = {
                            onUnlinkClick()
                            showDialog = false
                        }
                    ) {
                        Text("Unlink")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDialog = false }) {
                        Text("Cancel")
                    }
                }
            )
        }
    }
}
