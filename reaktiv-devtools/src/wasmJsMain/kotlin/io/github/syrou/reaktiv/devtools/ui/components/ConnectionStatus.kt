package io.github.syrou.reaktiv.devtools.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
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
internal fun ConnectionStatus(
    connectionState: ConnectionState,
    publisherName: String? = null,
    onReconnect: () -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
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
                ConnectionState.CONNECTING -> "Connecting"
                ConnectionState.DISCONNECTED -> "Disconnected"
                ConnectionState.ERROR -> "Connection lost"
            },
            style = MaterialTheme.typography.labelMedium
        )
        if (publisherName != null && connectionState == ConnectionState.CONNECTED) {
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "$publisherName publishing",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (connectionState == ConnectionState.ERROR || connectionState == ConnectionState.DISCONNECTED) {
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Reconnect",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clickable { onReconnect() }
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Anything shown below is the last data received.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.weight(1f))
    }
}
