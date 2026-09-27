package io.github.syrou.reaktiv.devtools.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

@Composable
internal fun OnboardingPanel(
    serverUrl: String,
    hasClients: Boolean,
    onImportGhost: () -> Unit
) {
    Box(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier.widthIn(max = 560.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = if (hasClients) "Waiting for a publisher" else "Waiting for devices",
                    style = MaterialTheme.typography.titleLarge
                )
                Text(
                    text = if (hasClients) {
                        "Devices are connected. Open the device panel and assign one as publisher, " +
                            "or configure a device with defaultRole = ClientRole.PUBLISHER."
                    } else {
                        "Point your app's DevToolsConfig at this server and dispatch an action."
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "Server endpoints",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                EndpointRow("This UI", serverUrl)
                EndpointRow("Android emulator", "ws://10.0.2.2:8080/ws")
                EndpointRow("Device on same network", "ws://<machine-ip>:8080/ws")
                Row(
                    modifier = Modifier.padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onImportGhost) {
                        Text("Import a session file instead")
                    }
                    Text(
                        text = "Ctrl+K for commands, ? for shortcuts",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun EndpointRow(label: String, url: String) {
    Row {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(170.dp)
        )
        Text(
            text = url,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace
        )
    }
}
