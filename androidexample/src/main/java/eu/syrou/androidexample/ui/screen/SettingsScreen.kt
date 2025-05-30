package eu.syrou.androidexample.ui.screen

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.annotation.RequiresApi
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import eu.syrou.androidexample.reaktiv.settings.SettingsModule
import io.github.syrou.reaktiv.compose.composeState
import io.github.syrou.reaktiv.compose.rememberStore
import io.github.syrou.reaktiv.navigation.transition.NavTransition
import io.github.syrou.reaktiv.navigation.alias.TitleResource
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.extension.navigate
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Serializable
object SettingsScreen : Screen {
    override val route: String = "settings"
    override val titleResource: TitleResource = {
        "Settings"
    }
    override val enterTransition: NavTransition = NavTransition.SlideInRight
    override val exitTransition: NavTransition = NavTransition.SlideOutLeft
    override val requiresAuth: Boolean = false

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    @Composable
    override fun Content(
        params: Map<String, Any>
    ) {
        val store = rememberStore()
        val context = LocalContext.current
        val settingsState by composeState<SettingsModule.SettingsState>()
        val lifecycleOwner = LocalLifecycleOwner.current
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        intent.data = Uri.fromParts("package", context.packageName, null)
        var hasNotificationPermission by remember {
            mutableStateOf(
                checkNotificationPermission(context)
            )
        }

        DisposableEffect(lifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    hasNotificationPermission = checkNotificationPermission(context)
                }
            }

            lifecycleOwner.lifecycle.addObserver(observer)

            onDispose {
                lifecycleOwner.lifecycle.removeObserver(observer)
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            TwitchAuthScreen(
                isLinked = settingsState.twitchAccessToken != null,
                onAuthorizeClick = {
                    store.launch {
                        store.navigate(TwitchAuthWebViewScreen.route)
                    }
                },
                onUnlinkClick = {
                    store.dispatch(SettingsModule.SettingsAction.SetTwitchAccessToken(null))
                }
            )
            Spacer(modifier = Modifier.height(8.dp))
            NotificationPermissionCard(hasNotificationPermission, {
                context.startActivity(intent)
            }, {
                context.startActivity(intent)
            })
            Button(onClick = {
                store.launch {
                    store.navigate("user/31") {
                        clearBackStack()
                    }
                }
            }) {
                Text("Navigate to user")
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun checkNotificationPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
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

    @Composable
    fun NotificationPermissionCard(
        isPermissionGranted: Boolean,
        onRequestPermission: () -> Unit,
        onRevokePermission: () -> Unit
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
                    text = if (isPermissionGranted) "Notifications Enabled" else "Enable Notifications",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = if (isPermissionGranted)
                        "You have granted permission for the app to send notifications."
                    else
                        "Allow the app to send you important updates and notifications.",
                    style = MaterialTheme.typography.bodyMedium
                )

                Spacer(modifier = Modifier.height(8.dp))

                if (isPermissionGranted) {
                    Button(
                        onClick = { showDialog = true },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("Revoke Permission")
                    }
                } else {
                    Button(onClick = onRequestPermission) {
                        Text("Grant Permission")
                    }
                }
            }
        }
        if (showDialog) {
            AlertDialog(
                onDismissRequest = { showDialog = false },
                title = { Text("Revoke Permission") },
                text = { Text("Are you sure you want to revoke notification permissions? You can always re-enable them in the app settings.") },
                confirmButton = {
                    Button(
                        onClick = {
                            onRevokePermission()
                            showDialog = false
                        }
                    ) {
                        Text("Revoke")
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