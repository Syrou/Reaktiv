package eu.syrou.androidexample.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.syrou.reaktiv.compose.composeState
import io.github.syrou.reaktiv.compose.rememberStore
import io.github.syrou.reaktiv.devtools.DevToolsAction
import io.github.syrou.reaktiv.devtools.DevToolsState
import io.github.syrou.reaktiv.devtools.client.ConnectionState
import io.github.syrou.reaktiv.navigation.alias.TitleResource
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.extension.navigateBack
import io.github.syrou.reaktiv.navigation.param.Params
import io.github.syrou.reaktiv.navigation.transition.NavTransition
import kotlinx.coroutines.launch

object DevToolsScreen : Screen {
    override val route: String = "devtools"
    override val titleResource: TitleResource = { "DevTools" }
    override val enterTransition: NavTransition = NavTransition.SlideInRight
    override val exitTransition: NavTransition = NavTransition.SlideOutLeft
    override val requiresAuth: Boolean = false

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content(params: Params) {
        val store = rememberStore()
        val scope = rememberCoroutineScope()
        val devToolsState by composeState<DevToolsState>()

        var serverIp by remember { mutableStateOf("192.168.1.100") }
        var serverPort by remember { mutableStateOf("8080") }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("DevTools Configuration") },
                    navigationIcon = {
                        IconButton(onClick = {
                            scope.launch {
                                store.navigateBack()
                            }
                        }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Connection Status Card
                ConnectionStatusCard(devToolsState)

                Spacer(modifier = Modifier.height(8.dp))

                // Server Configuration
                Text(
                    text = "Server Configuration",
                    style = MaterialTheme.typography.titleMedium
                )

                OutlinedTextField(
                    value = serverIp,
                    onValueChange = { serverIp = it },
                    label = { Text("Server IP Address") },
                    placeholder = { Text("192.168.1.100") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )

                OutlinedTextField(
                    value = serverPort,
                    onValueChange = { serverPort = it },
                    label = { Text("Port") },
                    placeholder = { Text("8080") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Action Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            val url = "ws://$serverIp:$serverPort/ws"
                            store.dispatch(DevToolsAction.Connect(url))
                        },
                        modifier = Modifier.weight(1f),
                        enabled = devToolsState.connectionState != ConnectionState.CONNECTING
                    ) {
                        Text("Connect")
                    }

                    OutlinedButton(
                        onClick = {
                            store.dispatch(DevToolsAction.Disconnect)
                        },
                        modifier = Modifier.weight(1f),
                        enabled = devToolsState.connectionState == ConnectionState.CONNECTED
                    ) {
                        Text("Disconnect")
                    }
                }

                if (devToolsState.connectionState == ConnectionState.ERROR ||
                    devToolsState.connectionState == ConnectionState.DISCONNECTED) {
                    OutlinedButton(
                        onClick = {
                            store.dispatch(DevToolsAction.Reconnect)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = devToolsState.currentServerUrl != null
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Reconnect to Last Server")
                    }
                }

                // Current Server Info
                devToolsState.currentServerUrl?.let { url ->
                    Spacer(modifier = Modifier.height(8.dp))
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "Current/Last Server",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = url,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }

                // Error Message
                devToolsState.errorMessage?.let { error ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = error,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectionStatusCard(state: DevToolsState) {
    val (statusColor, statusText, statusIcon) = when (state.connectionState) {
        ConnectionState.CONNECTED -> Triple(
            Color(0xFF4CAF50),
            "Connected",
            Icons.Default.CheckCircle
        )
        ConnectionState.CONNECTING -> Triple(
            Color(0xFFFFC107),
            "Connecting...",
            null
        )
        ConnectionState.DISCONNECTED -> Triple(
            Color(0xFF9E9E9E),
            "Disconnected",
            null
        )
        ConnectionState.ERROR -> Triple(
            Color(0xFFF44336),
            "Connection Error",
            Icons.Default.Warning
        )
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = statusColor.copy(alpha = 0.1f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            if (state.connectionState == ConnectionState.CONNECTING) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = statusColor,
                    strokeWidth = 2.dp
                )
            } else {
                statusIcon?.let { icon ->
                    Icon(
                        icon,
                        contentDescription = null,
                        tint = statusColor,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = statusText,
                style = MaterialTheme.typography.titleMedium,
                color = statusColor
            )
        }
    }
}
