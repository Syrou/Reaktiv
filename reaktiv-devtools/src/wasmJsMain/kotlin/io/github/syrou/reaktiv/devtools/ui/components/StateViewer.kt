package io.github.syrou.reaktiv.devtools.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import io.github.syrou.reaktiv.devtools.ui.ActionStateEvent
import io.github.syrou.reaktiv.devtools.ui.CrashEventInfo
import io.github.syrou.reaktiv.devtools.ui.LogicMethodEvent
import io.github.syrou.reaktiv.introspection.protocol.CrashException
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Displays state snapshot for the selected action or logic method data.
 */
@Composable
fun StateViewer(
    actionStateHistory: List<ActionStateEvent>,
    selectedActionIndex: Int?,
    logicMethodEvents: List<LogicMethodEvent> = emptyList(),
    selectedLogicMethodCallId: String? = null,
    crashEvent: CrashEventInfo? = null,
    crashSelected: Boolean = false,
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

    val selectedLogicEvents = selectedLogicMethodCallId?.let { callId ->
        logicMethodEvents.filter { it.callId == callId }
    } ?: emptyList()

    val isLogicMethodSelected = selectedLogicMethodCallId != null && selectedLogicEvents.isNotEmpty()
    val isCrashSelected = crashSelected && crashEvent != null

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
                text = when {
                    isCrashSelected -> "Crash Details"
                    isLogicMethodSelected -> "Logic Method Data"
                    else -> "State Snapshot"
                },
                style = MaterialTheme.typography.titleMedium
            )

            if (!isLogicMethodSelected && !isCrashSelected) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TextButton(onClick = onToggleDiffMode) {
                        Text(if (showAsDiff) "Show Full" else "Show Diff")
                    }
                }
            }
        }

        Divider(modifier = Modifier.padding(vertical = 8.dp))

        when {
            isCrashSelected && crashEvent != null -> {
                CrashDataView(crashEvent = crashEvent)
            }
            isLogicMethodSelected -> {
                LogicMethodDataView(
                    events = selectedLogicEvents
                )
            }
            selectedEvent == null -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp)
                ) {
                    Text(
                        text = "Select an action, logic method, or crash to view details",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            else -> {
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

@Composable
private fun LogicMethodDataView(
    events: List<LogicMethodEvent>
) {
    val startedEvent = events.filterIsInstance<LogicMethodEvent.Started>().firstOrNull()
    val completedEvent = events.filterIsInstance<LogicMethodEvent.Completed>().firstOrNull()
    val failedEvent = events.filterIsInstance<LogicMethodEvent.Failed>().firstOrNull()

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (startedEvent != null) {
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
                        text = "${startedEvent.logicClass}.${startedEvent.methodName}()",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = "Call ID: ${startedEvent.callId}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Text(
                        text = "Started: ${formatTimestamp(startedEvent.timestamp)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Text(
                        text = "Client: ${startedEvent.clientId}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (startedEvent.sourceFile != null && startedEvent.lineNumber != null) {
                        Spacer(modifier = Modifier.height(4.dp))
                        if (startedEvent.githubSourceUrl != null) {
                            Text(
                                text = "(${startedEvent.sourceFile}:${startedEvent.lineNumber})",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    textDecoration = TextDecoration.Underline
                                ),
                                color = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.clickable {
                                    openInBrowser(startedEvent.githubSourceUrl)
                                }
                            )
                        } else {
                            SelectionContainer {
                                Text(
                                    text = "(${startedEvent.sourceFile}:${startedEvent.lineNumber})",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.tertiary
                                )
                            }
                        }
                    }
                }
            }
        }

        if (startedEvent != null && startedEvent.params.isNotEmpty()) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 200.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp)
                ) {
                    Text(
                        text = "Parameters",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        LazyColumn(
                            modifier = Modifier.padding(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            items(startedEvent.params.entries.toList().size) { index ->
                                val (key, value) = startedEvent.params.entries.toList()[index]
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = "$key:",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        text = value,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        if (completedEvent != null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp)
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                    ) {
                        Surface(
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                            shape = MaterialTheme.shapes.small
                        ) {
                            Text(
                                text = "COMPLETED",
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Text(
                            text = "${completedEvent.durationMs}ms",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "Return Type: ${completedEvent.resultType}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    completedEvent.result?.let { result ->
                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = "Result",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        Surface(
                            color = MaterialTheme.colorScheme.surface,
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.heightIn(max = 200.dp)
                        ) {
                            val scrollState = rememberScrollState()
                            SelectionContainer {
                                Text(
                                    text = result,
                                    modifier = Modifier
                                        .padding(8.dp)
                                        .verticalScroll(scrollState),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }
            }
        }

        if (failedEvent != null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp)
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                    ) {
                        Surface(
                            color = MaterialTheme.colorScheme.error.copy(alpha = 0.2f),
                            shape = MaterialTheme.shapes.small
                        ) {
                            Text(
                                text = "FAILED",
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        Text(
                            text = "${failedEvent.durationMs}ms",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "Exception: ${failedEvent.exceptionType}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )

                    failedEvent.exceptionMessage?.let { message ->
                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = "Message",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.error
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        Surface(
                            color = MaterialTheme.colorScheme.surface,
                            shape = MaterialTheme.shapes.small
                        ) {
                            Text(
                                text = message,
                                modifier = Modifier.padding(8.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
        }

        if (startedEvent == null && completedEvent == null && failedEvent == null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(32.dp)
            ) {
                Text(
                    text = "No data available for this logic method call",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun formatTimestamp(timestamp: Long): String {
    val instant = Instant.fromEpochMilliseconds(timestamp)
    val dateTime = instant.toLocalDateTime(TimeZone.currentSystemDefault())
    val millis = (timestamp % 1000).toString().padStart(3, '0')

    return "${dateTime.hour.toString().padStart(2, '0')}:${dateTime.minute.toString().padStart(2, '0')}:${dateTime.second.toString().padStart(2, '0')}:${millis}"
}

private fun openInBrowser(url: String) {
    js("window.open(url, '_blank')")
}

@Composable
private fun CrashDataView(crashEvent: CrashEventInfo) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp)
            ) {
                Text(
                    text = crashEvent.exception.exceptionType,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.error
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = "Timestamp: ${formatTimestamp(crashEvent.timestamp)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )

                Text(
                    text = "Client: ${crashEvent.clientId}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )

                val message = crashEvent.exception.message
                if (message != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }

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
                    text = "Stack Trace",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.error
                )

                Spacer(modifier = Modifier.height(8.dp))

                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxSize()
                ) {
                    val scrollState = rememberScrollState()
                    SelectionContainer {
                        Column(
                            modifier = Modifier
                                .padding(8.dp)
                                .verticalScroll(scrollState)
                        ) {
                            Text(
                                text = crashEvent.exception.stackTrace,
                                style = MaterialTheme.typography.bodySmall
                            )

                            val causedBy = crashEvent.exception.causedBy
                            if (causedBy != null) {
                                Spacer(modifier = Modifier.height(16.dp))
                                CausedByView(causedBy)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CausedByView(exception: CrashException) {
    Column {
        Text(
            text = "Caused by: ${exception.exceptionType}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.error
        )

        val message = exception.message
        if (message != null) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = exception.stackTrace,
            style = MaterialTheme.typography.bodySmall
        )

        val nestedCausedBy = exception.causedBy
        if (nestedCausedBy != null) {
            Spacer(modifier = Modifier.height(16.dp))
            CausedByView(nestedCausedBy)
        }
    }
}
