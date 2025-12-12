package io.github.syrou.reaktiv.devtools.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
    onPublisherSelected: (String?) -> Unit,
    onListenerSelected: (String?) -> Unit,
    onAssignRole: (String, String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(16.dp)
    ) {
        Text(
            text = "Connected Clients (${clients.size})",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(bottom = 8.dp)
        )

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

        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(clients) { client ->
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
private fun RoleBadge(role: ClientRole, publisherClientId: String?) {
    val roleText = when (role) {
        ClientRole.PUBLISHER -> "PUBLISHER"
        ClientRole.SUBSCRIBER -> "SUBSCRIBER${publisherClientId?.let { " ($it)" } ?: ""}"
        ClientRole.ORCHESTRATOR -> "ORCHESTRATOR${publisherClientId?.let { " ($it)" } ?: ""}"
        ClientRole.UNASSIGNED -> "UNASSIGNED"
    }

    val roleColor = when (role) {
        ClientRole.PUBLISHER -> MaterialTheme.colorScheme.primary
        ClientRole.SUBSCRIBER -> MaterialTheme.colorScheme.secondary
        ClientRole.ORCHESTRATOR -> MaterialTheme.colorScheme.tertiary
        ClientRole.UNASSIGNED -> MaterialTheme.colorScheme.outline
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
