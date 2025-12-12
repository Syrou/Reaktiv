package io.github.syrou.reaktiv.devtools.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.syrou.reaktiv.devtools.ui.ActionStateEvent
import kotlin.time.Duration.Companion.milliseconds

/**
 * Displays state snapshot for the selected action.
 */
@Composable
fun StateViewer(
    actionStateHistory: List<ActionStateEvent>,
    selectedActionIndex: Int?,
    showAsDiff: Boolean,
    excludedActionTypes: Set<String>,
    onToggleDiffMode: () -> Unit,
    onClear: () -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }

    val selectedEvent = selectedActionIndex?.let { actionStateHistory.getOrNull(it) }
    val previousEvent = if (showAsDiff && selectedEvent != null && selectedActionIndex != null) {
        if (selectedActionIndex > 0) {
            actionStateHistory.subList(0, selectedActionIndex)
                .findLast { !excludedActionTypes.contains(it.actionType) }
        } else null
    } else {
        null
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "State Snapshot",
                style = MaterialTheme.typography.titleMedium
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TextButton(onClick = onToggleDiffMode) {
                    Text(if (showAsDiff) "Show Full" else "Show Diff")
                }
            }
        }

        Divider(modifier = Modifier.padding(vertical = 8.dp))

        if (selectedEvent == null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(32.dp)
            ) {
                Text(
                    text = "Select an action to view its state",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            StateSnapshotView(
                event = selectedEvent,
                previousEvent = previousEvent,
                showAsDiff = showAsDiff,
                searchQuery = searchQuery,
                onSearchQueryChange = { searchQuery = it }
            )
        }
    }
}

@Composable
private fun StateSnapshotView(
    event: ActionStateEvent,
    previousEvent: ActionStateEvent?,
    showAsDiff: Boolean,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp)
            ) {
                Text(
                    text = "For Action: ${event.actionType}",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = "Timestamp: ${formatTimestamp(event.timestamp)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Text(
                    text = "Client: ${event.clientId}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Text(
                    text = "Size: ${event.resultingStateJson.length} bytes",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchQueryChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Filter properties...") },
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surface,
                unfocusedContainerColor = MaterialTheme.colorScheme.surface
            )
        )

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp)
            ) {
                Text(
                    text = "State Data",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )

                Spacer(modifier = Modifier.height(8.dp))

                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxSize()
                ) {
                    JsonTreeViewer(
                        jsonString = event.resultingStateJson,
                        previousJsonString = previousEvent?.resultingStateJson,
                        searchQuery = searchQuery,
                        showDiff = showAsDiff && previousEvent != null,
                        modifier = Modifier.padding(8.dp)
                    )
                }
            }
        }
    }
}

private fun formatTimestamp(timestamp: Long): String {
    val duration = timestamp.milliseconds
    val hours = duration.inWholeHours
    val minutes = (duration.inWholeMinutes % 60)
    val seconds = (duration.inWholeSeconds % 60)
    val millis = (duration.inWholeMilliseconds % 1000)

    return "${hours.toString().padStart(2, '0')}:${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}.${millis.toString().padStart(3, '0')}"
}
