package io.github.syrou.reaktiv.devtools.ui.components

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.syrou.reaktiv.devtools.protocol.ClientInfo
import io.github.syrou.reaktiv.devtools.protocol.ClientRole

/**
 * Displays the list of connected clients with their roles and platforms.
 */
@Composable
fun ClientList(
    clients: List<ClientInfo>,
    selectedPublisher: String?,
    selectedListener: String?,
    canExportSession: Boolean = false,
    onPublisherSelected: (String?) -> Unit,
    onListenerSelected: (String?) -> Unit,
    onAssignRole: (String, String) -> Unit,
    onRemoveGhost: (String) -> Unit = {},
    onImportGhost: () -> Unit = {},
    onExportSession: () -> Unit = {}
) {
    val realClients = clients.filter { !it.isGhost }
    val ghostClients = clients.filter { it.isGhost }

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
            Text(
                text = "Connected Clients (${clients.size})",
                style = MaterialTheme.typography.titleMedium
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (canExportSession) {
                    IconButton(onClick = onExportSession) {
                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = "Export Session",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                IconButton(onClick = onImportGhost) {
                    Icon(
                        imageVector = Icons.Default.Upload,
                        contentDescription = "Import Ghost Session",
                        tint = MaterialTheme.colorScheme.tertiary
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (selectedPublisher != null && selectedListener != null) {
            Button(
                onClick = { onAssignRole(selectedListener, selectedPublisher) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
            ) {
                val publisherName = clients.find { it.clientId == selectedPublisher }?.clientName
                val listenerName = clients.find { it.clientId == selectedListener }?.clientName
                Text("Assign \"$listenerName\" to listen to \"$publisherName\"")
            }
        }

        Divider(modifier = Modifier.padding(vertical = 8.dp))

        val listState = rememberLazyListState()

        Box(modifier = Modifier.fillMaxWidth()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth().padding(end = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (ghostClients.isNotEmpty()) {
                    item {
                        Text(
                            text = "Ghost Devices",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }

                    items(ghostClients, key = { it.clientId }) { client ->
                        GhostClientCard(
                            client = client,
                            isSelectedAsPublisher = client.clientId == selectedPublisher,
                            onPublisherClick = {
                                onPublisherSelected(
                                    if (client.clientId == selectedPublisher) null else client.clientId
                                )
                            },
                            onRemove = { onRemoveGhost(client.clientId) }
                        )
                    }

                    if (realClients.isNotEmpty()) {
                        item {
                            Divider(modifier = Modifier.padding(vertical = 8.dp))
                            Text(
                                text = "Live Devices",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(vertical = 4.dp)
                            )
                        }
                    }
                }

                items(realClients, key = { it.clientId }) { client ->
                    ClientCard(
                        client = client,
                        isSelectedAsPublisher = client.clientId == selectedPublisher,
                        isSelectedAsListener = client.clientId == selectedListener,
                        onPublisherClick = {
                            onPublisherSelected(
                                if (client.clientId == selectedPublisher) null else client.clientId
                            )
                        },
                        onListenerClick = {
                            onListenerSelected(
                                if (client.clientId == selectedListener) null else client.clientId
                            )
                        }
                    )
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
private fun ClientCard(
    client: ClientInfo,
    isSelectedAsPublisher: Boolean,
    isSelectedAsListener: Boolean,
    onPublisherClick: () -> Unit,
    onListenerClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when {
                isSelectedAsPublisher && isSelectedAsListener -> MaterialTheme.colorScheme.tertiaryContainer
                isSelectedAsPublisher -> MaterialTheme.colorScheme.primaryContainer
                isSelectedAsListener -> MaterialTheme.colorScheme.secondaryContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = client.clientName,
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = client.platform,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                RoleBadge(client.role, client.publisherClientId)
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onPublisherClick,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isSelectedAsPublisher)
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.outline
                    )
                ) {
                    Text("Publisher", style = MaterialTheme.typography.bodySmall)
                }

                Button(
                    onClick = onListenerClick,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isSelectedAsListener)
                            MaterialTheme.colorScheme.secondary
                        else
                            MaterialTheme.colorScheme.outline
                    )
                ) {
                    Text("Listener", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun GhostClientCard(
    client: ClientInfo,
    isSelectedAsPublisher: Boolean,
    onPublisherClick: () -> Unit,
    onRemove: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 2.dp,
                color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.5f),
                shape = MaterialTheme.shapes.medium
            ),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelectedAsPublisher) {
                MaterialTheme.colorScheme.tertiaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
            }
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = client.clientName,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                    Text(
                        text = client.platform,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.tertiary,
                        shape = MaterialTheme.shapes.small
                    ) {
                        Text(
                            text = "GHOST",
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onTertiary
                        )
                    }

                    IconButton(onClick = onRemove) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Remove ghost",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = onPublisherClick,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isSelectedAsPublisher)
                        MaterialTheme.colorScheme.tertiary
                    else
                        MaterialTheme.colorScheme.outline
                )
            ) {
                Text("Select as Publisher", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun RoleBadge(role: ClientRole, publisherClientId: String?, isGhost: Boolean = false) {
    val roleText = when {
        isGhost -> "GHOST"
        role == ClientRole.PUBLISHER -> "PUBLISHER"
        role == ClientRole.SUBSCRIBER -> "LISTENER${publisherClientId?.let { " ($it)" } ?: ""}"
        role == ClientRole.ORCHESTRATOR -> "ORCHESTRATOR${publisherClientId?.let { " ($it)" } ?: ""}"
        else -> "UNASSIGNED"
    }

    val roleColor = when {
        isGhost -> MaterialTheme.colorScheme.tertiary
        role == ClientRole.PUBLISHER -> MaterialTheme.colorScheme.primary
        role == ClientRole.SUBSCRIBER -> MaterialTheme.colorScheme.secondary
        role == ClientRole.ORCHESTRATOR -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.outline
    }

    Surface(
        color = roleColor,
        shape = MaterialTheme.shapes.small
    ) {
        Text(
            text = roleText,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimary
        )
    }
}
