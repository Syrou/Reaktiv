package io.github.syrou.reaktiv.devtools.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import io.github.syrou.reaktiv.devtools.ui.ActionStateEvent
import kotlin.time.Duration.Companion.milliseconds

/**
 * Displays a stream of actions dispatched by publisher clients.
 */
@Composable
fun ActionStream(
    actions: List<ActionStateEvent>,
    selectedIndex: Int? = null,
    autoSelectLatest: Boolean = true,
    excludedActionTypes: Set<String>,
    onSelectAction: (Int?) -> Unit = {},
    onToggleAutoSelect: () -> Unit = {},
    onAddExclusion: (String) -> Unit = {},
    onRemoveExclusion: (String) -> Unit = {},
    onSetExclusions: (Set<String>) -> Unit = {},
    onClear: () -> Unit
) {
    val listState = rememberLazyListState()
    var exclusionInput by remember { mutableStateOf("") }

    // Filter out excluded actions
    val filteredActions = actions.filterIndexed { index, action ->
        !excludedActionTypes.contains(action.actionType)
    }
    val reversedActions = filteredActions.reversed()

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

        // Exclusion filter section
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Exclude Actions",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedTextField(
                    value = exclusionInput,
                    onValueChange = { exclusionInput = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Type action name and press Enter (or use + button)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            if (exclusionInput.isNotBlank()) {
                                val newExclusions = exclusionInput.split(",")
                                    .map { it.trim() }
                                    .filter { it.isNotBlank() }
                                    .toSet()
                                onSetExclusions(excludedActionTypes + newExclusions)
                                exclusionInput = ""
                            }
                        }
                    ),
                    trailingIcon = {
                        if (exclusionInput.isNotBlank()) {
                            IconButton(onClick = {
                                val newExclusions = exclusionInput.split(",")
                                    .map { it.trim() }
                                    .filter { it.isNotBlank() }
                                    .toSet()
                                onSetExclusions(excludedActionTypes + newExclusions)
                                exclusionInput = ""
                            }) {
                                Text("+", style = MaterialTheme.typography.titleMedium)
                            }
                        }
                    }
                )

                // Display current exclusions as chips
                if (excludedActionTypes.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.Start)
                    ) {
                        excludedActionTypes.forEach { actionType ->
                            FilterChip(
                                selected = true,
                                onClick = { onRemoveExclusion(actionType) },
                                label = { Text(actionType, style = MaterialTheme.typography.bodySmall) },
                                trailingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Remove exclusion",
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (filteredActions.isEmpty()) {
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
                    val originalIndex = actions.indexOf(action)
                    ActionEventCard(
                        action = action,
                        isSelected = originalIndex == selectedIndex,
                        onClick = { onSelectAction(originalIndex) },
                        onExclude = { onAddExclusion(action.actionType) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ActionEventCard(
    action: ActionStateEvent,
    isSelected: Boolean = false,
    onClick: () -> Unit = {},
    onExclude: () -> Unit = {}
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
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = action.actionType,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = onExclude,
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.VisibilityOff,
                            contentDescription = "Exclude this action type",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    Text(
                        text = formatTimestamp(action.timestamp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
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
