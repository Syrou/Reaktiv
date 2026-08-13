package io.github.syrou.reaktiv.devtools.ui.components

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.PhoneIphone
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.syrou.reaktiv.devtools.protocol.ClientInfo
import io.github.syrou.reaktiv.devtools.protocol.ClientRole
import io.github.syrou.reaktiv.introspection.tooling.ServiceState
import io.github.syrou.reaktiv.introspection.tooling.ServiceStatus

private fun platformIcon(platform: String): ImageVector {
    val normalized = platform.lowercase()
    return when {
        "android" in normalized -> Icons.Default.Android
        "ios" in normalized || "iphone" in normalized || "ipad" in normalized -> Icons.Default.PhoneIphone
        "wasm" in normalized || "browser" in normalized || "web" in normalized -> Icons.Default.Language
        else -> Icons.Default.Computer
    }
}

@Composable
internal fun ClientList(
    clients: List<ClientInfo>,
    selectedPublisher: String?,
    selectedListener: String?,
    clientStatuses: Map<String, ServiceStatus> = emptyMap(),
    canExportSession: Boolean = false,
    onPublisherSelected: (String?) -> Unit,
    onListenerSelected: (String?) -> Unit,
    onAssignRole: (String, String) -> Unit,
    onRemoveGhost: (String) -> Unit = {},
    onImportGhost: () -> Unit = {},
    onExportSession: () -> Unit = {}
) {
    val devices = clients.filter { !it.isGhost && it.role != ClientRole.ORCHESTRATOR }
    val ghosts = clients.filter { it.isGhost }
    val observers = clients.count { !it.isGhost && it.role == ClientRole.ORCHESTRATOR }

    val publisherDevices = devices.filter {
        it.role == ClientRole.PUBLISHER || it.clientId == selectedPublisher
    }
    val followerDevices = devices.filter {
        it.role == ClientRole.LISTENER && it.clientId !in publisherDevices.map { p -> p.clientId }
    }
    val availableDevices = devices.filter { device ->
        publisherDevices.none { it.clientId == device.clientId } &&
            followerDevices.none { it.clientId == device.clientId }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Devices",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = buildString {
                        append(devices.size)
                        append(if (devices.size == 1) " device" else " devices")
                        if (ghosts.isNotEmpty()) append(", ${ghosts.size} saved")
                        if (observers > 0) append(", $observers observing")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (canExportSession) {
                    IconButton(onClick = onExportSession) {
                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = "Export session",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                TextButton(onClick = onImportGhost) {
                    Text("Import session")
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        val listState = rememberLazyListState()

        Box(modifier = Modifier.fillMaxWidth()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth().padding(end = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (publisherDevices.isNotEmpty()) {
                    item(key = "section-publisher") {
                        SectionLabel("Publisher", MaterialTheme.colorScheme.primary)
                    }
                    items(publisherDevices, key = { it.clientId }) { client ->
                        DeviceCard(
                            client = client,
                            status = clientStatuses[client.clientId],
                            accent = MaterialTheme.colorScheme.primary,
                            highlighted = true,
                            roleLine = "Publishing this session",
                            actions = {
                                if (canExportSession) {
                                    TextButton(onClick = onExportSession) {
                                        Text("Export", style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                                TextButton(onClick = { onPublisherSelected(null) }) {
                                    Text("Deselect", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        )
                    }
                }

                if (followerDevices.isNotEmpty()) {
                    item(key = "section-followers") {
                        SectionLabel("Followers", MaterialTheme.colorScheme.secondary)
                    }
                    items(followerDevices, key = { it.clientId }) { client ->
                        DeviceCard(
                            client = client,
                            status = clientStatuses[client.clientId],
                            accent = MaterialTheme.colorScheme.secondary,
                            highlighted = client.clientId == selectedListener,
                            roleLine = client.publisherClientId?.let { "Following $it" } ?: "Following",
                            actions = {
                                TextButton(onClick = { onPublisherSelected(client.clientId) }) {
                                    Text("Make publisher", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        )
                    }
                }

                if (availableDevices.isNotEmpty()) {
                    item(key = "section-available") {
                        SectionLabel("Available", MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    items(availableDevices, key = { it.clientId }) { client ->
                        DeviceCard(
                            client = client,
                            status = clientStatuses[client.clientId],
                            accent = MaterialTheme.colorScheme.outline,
                            highlighted = false,
                            roleLine = "Not assigned",
                            actions = {
                                TextButton(onClick = { onPublisherSelected(client.clientId) }) {
                                    Text("Make publisher", style = MaterialTheme.typography.labelSmall)
                                }
                                if (selectedPublisher != null && selectedPublisher != client.clientId) {
                                    TextButton(onClick = {
                                        onListenerSelected(client.clientId)
                                        onAssignRole(client.clientId, selectedPublisher)
                                    }) {
                                        Text("Follow publisher", style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                        )
                    }
                }

                if (devices.isEmpty()) {
                    item(key = "no-devices") {
                        Text(
                            text = "No live devices connected",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                }

                if (ghosts.isNotEmpty()) {
                    item(key = "section-ghosts") {
                        SectionLabel("Saved sessions", MaterialTheme.colorScheme.tertiary)
                    }
                    items(ghosts, key = { it.clientId }) { client ->
                        DeviceCard(
                            client = client,
                            status = null,
                            accent = MaterialTheme.colorScheme.tertiary,
                            highlighted = client.clientId == selectedPublisher,
                            roleLine = "Recorded session",
                            icon = Icons.Default.History,
                            actions = {
                                TextButton(onClick = {
                                    onPublisherSelected(
                                        if (client.clientId == selectedPublisher) null else client.clientId
                                    )
                                }) {
                                    Text(
                                        if (client.clientId == selectedPublisher) "Unload" else "Load",
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                                IconButton(
                                    onClick = { onRemoveGhost(client.clientId) },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Remove saved session",
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        )
                    }
                }
            }

            VerticalScrollbar(
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                adapter = rememberScrollbarAdapter(listState)
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String, color: Color) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = color,
        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
    )
}

@Composable
private fun DeviceCard(
    client: ClientInfo,
    status: ServiceStatus?,
    accent: Color,
    highlighted: Boolean,
    roleLine: String,
    icon: ImageVector = platformIcon(client.platform),
    actions: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (highlighted) {
                MaterialTheme.colorScheme.surfaceVariant
            } else {
                MaterialTheme.colorScheme.surface
            }
        )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = client.platform,
                tint = accent,
                modifier = Modifier.size(22.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = client.clientName,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    HealthDot(status)
                }
                Text(
                    text = "${client.platform}  $roleLine",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val detail = status?.detail
                if (detail != null) {
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (status.state == ServiceState.DEGRADED) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                actions()
            }
        }
    }
}

@Composable
private fun HealthDot(status: ServiceStatus?) {
    val color = when (status?.state) {
        ServiceState.RUNNING -> Color(0xFF4CAF50)
        ServiceState.DEGRADED -> MaterialTheme.colorScheme.error
        null -> MaterialTheme.colorScheme.outlineVariant
        else -> MaterialTheme.colorScheme.tertiary
    }
    Surface(
        color = color,
        shape = CircleShape,
        modifier = Modifier.size(7.dp).clip(CircleShape)
    ) {}
}
