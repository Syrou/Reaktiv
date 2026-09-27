package eu.syrou.example.tooling

import io.github.syrou.reaktiv.core.util.selectLogic
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.syrou.reaktiv.compose.composeState
import io.github.syrou.reaktiv.compose.rememberStore
import io.github.syrou.reaktiv.devtools.protocol.ClientRole
import io.github.syrou.reaktiv.devtools.service.DevToolsCommands
import io.github.syrou.reaktiv.introspection.tooling.ServiceState
import io.github.syrou.reaktiv.introspection.tooling.ServiceStatus
import io.github.syrou.reaktiv.introspection.tooling.ToolingState
import io.github.syrou.reaktiv.navigation.alias.TitleResource
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.param.Params
import io.github.syrou.reaktiv.navigation.transition.NavTransition
import kotlinx.coroutines.launch
import io.github.syrou.reaktiv.navigation.extension.navigation

object DevToolsScreen : Screen {
    override val route: String = "devtools"
    override val titleResource: TitleResource = { "DevTools" }
    override val enterTransition: NavTransition = NavTransition.SlideInRight
    override val exitTransition: NavTransition = NavTransition.SlideOutLeft

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content(params: Params) {
        val store = rememberStore()
        val toolingState by composeState<ToolingState>()
        val form by composeState<DevToolsFormState>()

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Debug Menu") },
                    navigationIcon = {
                        IconButton(onClick = {
                            store.launch {
                                store.navigation { navigateBack() }
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
                Text("Services", style = MaterialTheme.typography.titleMedium)
                if (toolingState.services.isEmpty()) {
                    Text(
                        "No tooling services running",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                toolingState.services.forEach { (serviceName, status) ->
                    ServiceStatusCard(serviceName, status)
                }
                Text(
                    if (toolingState.isCapturing) "Session capture active" else "Session capture stopped",
                    style = MaterialTheme.typography.bodyMedium
                )

                Text("Server", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = form.serverUrl,
                    onValueChange = { store.dispatch(DevToolsFormAction.SetServerUrl(it)) },
                    label = { Text("Server URL") },
                    placeholder = { Text("ws://192.168.1.20:8080/ws") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                )

                Text("Connect as", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(ClientRole.PUBLISHER, ClientRole.LISTENER, ClientRole.UNASSIGNED).forEach { role ->
                        FilterChip(
                            selected = form.role == role,
                            onClick = { store.dispatch(DevToolsFormAction.SetRole(role)) },
                            label = { Text(role.name.lowercase().replaceFirstChar { it.uppercase() }) }
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            store.dispatch(
                                DevToolsCommands.connect(
                                    url = form.serverUrl.trim().ifEmpty { null },
                                    role = form.role.takeIf { it != ClientRole.UNASSIGNED }
                                )
                            )
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Connect")
                    }
                    OutlinedButton(
                        onClick = { store.dispatch(DevToolsCommands.disconnect()) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Disconnect")
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { store.dispatch(DevToolsCommands.follow()) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Follow")
                    }
                    OutlinedButton(
                        onClick = { store.dispatch(DevToolsCommands.unfollow()) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Unfollow")
                    }
                    OutlinedButton(
                        onClick = { store.dispatch(DevToolsCommands.reconnect()) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Reconnect")
                    }
                }

                Text("Network probes", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Sends a request whose response cannot be decoded into its data class, so the " +
                        "failure shows up in the DevTools network lane, the log lane and findings",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    NetworkProbe.entries.forEach { probe ->
                        OutlinedButton(
                            onClick = {
                                store.launch { store.selectLogic<DevToolsFormLogic>().probe(probe) }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(probe.label)
                        }
                    }
                }
                form.probeResult?.let { result ->
                    Text(
                        result,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun ServiceStatusCard(serviceName: String, status: ServiceStatus) {
    val statusColor = when (status.state) {
        ServiceState.RUNNING -> Color(0xFF4CAF50)
        ServiceState.STARTING -> Color(0xFFFFC107)
        ServiceState.DEGRADED -> Color(0xFFF44336)
        ServiceState.STOPPED -> Color(0xFF9E9E9E)
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = statusColor.copy(alpha = 0.1f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(serviceName, style = MaterialTheme.typography.titleSmall, color = statusColor)
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                "${status.state.name.lowercase()}${status.detail?.let { ": $it" } ?: ""}",
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}
