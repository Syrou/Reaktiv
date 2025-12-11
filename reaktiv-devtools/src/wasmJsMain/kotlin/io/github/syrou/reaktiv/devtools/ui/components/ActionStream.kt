package io.github.syrou.reaktiv.devtools.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.syrou.reaktiv.devtools.ui.ActionEvent
import kotlin.time.Duration.Companion.milliseconds

/**
 * Displays a stream of actions dispatched by publisher clients.
 */
@Composable
fun ActionStream(
    actions: List<ActionEvent>,
    selectedIndex: Int? = null,
    autoSelectLatest: Boolean = true,
    onSelectAction: (Int?) -> Unit = {},
    onToggleAutoSelect: () -> Unit = {},
    onClear: () -> Unit
) {
    val listState = rememberLazyListState()
    val reversedActions = actions.reversed()

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
                text = "Action History (${actions.size})",
                style = MaterialTheme.typography.titleMedium
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TextButton(onClick = onToggleAutoSelect) {
                    Text(if (autoSelectLatest) "Auto: ON" else "Auto: OFF")
                }

                if (actions.isNotEmpty()) {
                    TextButton(onClick = onClear) {
                        Text("Clear")
                    }
                }
            }
        }

        Divider(modifier = Modifier.padding(vertical = 8.dp))

        if (actions.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(32.dp)
            ) {
                Text(
                    text = "No actions dispatched yet",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(reversedActions) { reversedIndex, action ->
                    val originalIndex = actions.size - 1 - reversedIndex
                    ActionEventCard(
                        action = action,
                        isSelected = originalIndex == selectedIndex,
                        onClick = { onSelectAction(originalIndex) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ActionEventCard(
    action: ActionEvent,
    isSelected: Boolean = false,
    onClick: () -> Unit = {}
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .then(
                if (isSelected) {
                    Modifier.border(
                        width = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                        shape = MaterialTheme.shapes.medium
                    )
                } else Modifier
            ),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
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
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = action.actionType,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary
                )

                Text(
                    text = formatTimestamp(action.timestamp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "Client: ${action.clientId}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (action.actionData.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))

                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = MaterialTheme.shapes.small
                ) {
                    Text(
                        text = action.actionData,
                        modifier = Modifier.padding(8.dp),
                        style = MaterialTheme.typography.bodySmall
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
