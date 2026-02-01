package io.github.syrou.reaktiv.devtools.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.syrou.reaktiv.devtools.client.ConnectionState
import io.github.syrou.reaktiv.devtools.ui.DevToolsColors

/**
 * Displays the current connection status to the DevTools server.
 */
@Composable
fun ConnectionStatus(
    connectionState: ConnectionState,
    deviceCount: Int = 0,
    isDevicePanelExpanded: Boolean = false,
    onToggleDevicePanel: () -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .background(
                    color = when (connectionState) {
                        ConnectionState.CONNECTED -> DevToolsColors.success
                        ConnectionState.CONNECTING -> DevToolsColors.warning
                        ConnectionState.DISCONNECTED -> DevToolsColors.onSurfaceVariant
                        ConnectionState.ERROR -> DevToolsColors.error
                    },
                    shape = CircleShape
                )
        )

        Spacer(modifier = Modifier.width(8.dp))

        Text(
            text = when (connectionState) {
                ConnectionState.CONNECTED -> "Connected"
                ConnectionState.CONNECTING -> "Connecting..."
                ConnectionState.DISCONNECTED -> "Disconnected"
                ConnectionState.ERROR -> "Connection Error"
            },
            style = MaterialTheme.typography.bodyMedium
        )

        Spacer(modifier = Modifier.weight(1f))

        Row(
            modifier = Modifier
                .clickable { onToggleDevicePanel() }
                .padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (isDevicePanelExpanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowRight,
                contentDescription = if (isDevicePanelExpanded) "Collapse devices" else "Expand devices",
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = "Devices ($deviceCount)",
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}
