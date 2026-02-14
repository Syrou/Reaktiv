package io.github.syrou.reaktiv.devtools.ui.components

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import io.github.syrou.reaktiv.devtools.ui.ActionStateEvent
import io.github.syrou.reaktiv.devtools.ui.CrashEventInfo
import io.github.syrou.reaktiv.devtools.ui.LogicMethodEvent
import io.github.syrou.reaktiv.introspection.protocol.CapturedAction
import io.github.syrou.reaktiv.introspection.protocol.CrashException
import io.github.syrou.reaktiv.introspection.protocol.StateReconstructor
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Displays state snapshot for the selected action or logic method data.
 */
/**
 * State viewer tab selection.
 */
private enum class StateViewerTab { DELTA, STATE }

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
    initialStateJson: String = "{}",
    onToggleDiffMode: () -> Unit,
    onClear: () -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var activeTab by remember { mutableStateOf(StateViewerTab.DELTA) }

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
    val isActionSelected = selectedEvent != null && !isLogicMethodSelected && !isCrashSelected

    // Reconstruct state at crash time
    val crashReconstructedState = remember(isCrashSelected, crashEvent?.timestamp, initialStateJson, actionStateHistory.size) {
        if (!isCrashSelected || crashEvent == null) return@remember null
        if (actionStateHistory.isEmpty()) return@remember initialStateJson
        val lastActionIndex = actionStateHistory.indexOfLast { it.timestamp <= crashEvent.timestamp }
        if (lastActionIndex < 0) return@remember initialStateJson
        val capturedActions = actionStateHistory.map { event ->
            CapturedAction(
                clientId = event.clientId,
                timestamp = event.timestamp,
                actionType = event.actionType,
                actionData = event.actionData,
                stateDeltaJson = event.stateDeltaJson,
                moduleName = event.moduleName
            )
        }
        StateReconstructor.reconstructAtIndex(initialStateJson, capturedActions, lastActionIndex)
    }

    // Cache reconstructed states for the State tab
    val reconstructedState = remember(selectedActionIndex, initialStateJson, actionStateHistory.size) {
        if (selectedActionIndex == null || !isActionSelected) return@remember null
        val capturedActions = actionStateHistory.map { event ->
            CapturedAction(
                clientId = event.clientId,
                timestamp = event.timestamp,
                actionType = event.actionType,
                actionData = event.actionData,
                stateDeltaJson = event.stateDeltaJson,
                moduleName = event.moduleName
            )
        }
        StateReconstructor.reconstructAtIndex(initialStateJson, capturedActions, selectedActionIndex)
    }

    val previousReconstructedState = remember(selectedActionIndex, initialStateJson, actionStateHistory.size, showAsDiff) {
        if (!showAsDiff || selectedActionIndex == null || !isActionSelected) return@remember null
        if (selectedActionIndex <= 0) return@remember null
        val prevIndex = run {
            var idx = selectedActionIndex - 1
            while (idx >= 0 && excludedActionTypes.contains(actionStateHistory[idx].actionType)) {
                idx--
            }
            if (idx >= 0) idx else null
        } ?: return@remember null
        val capturedActions = actionStateHistory.map { event ->
            CapturedAction(
                clientId = event.clientId,
                timestamp = event.timestamp,
                actionType = event.actionType,
                actionData = event.actionData,
                stateDeltaJson = event.stateDeltaJson,
                moduleName = event.moduleName
            )
        }
        StateReconstructor.reconstructAtIndex(initialStateJson, capturedActions, prevIndex)
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
                text = when {
                    isCrashSelected -> "Crash Details"
                    isLogicMethodSelected -> "Logic Method Data"
                    else -> "State Snapshot"
                },
                style = MaterialTheme.typography.titleMedium
            )

            if (isActionSelected) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TextButton(onClick = onToggleDiffMode) {
                        Text(if (showAsDiff) "Show Full" else "Show Diff")
                    }
                }
            }
        }

        if (isActionSelected) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                TextButton(
                    onClick = { activeTab = StateViewerTab.DELTA },
                    colors = if (activeTab == StateViewerTab.DELTA) {
                        ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.primary
                        )
                    } else {
                        ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                ) {
                    Text("Delta")
                }
                TextButton(
                    onClick = { activeTab = StateViewerTab.STATE },
                    colors = if (activeTab == StateViewerTab.STATE) {
                        ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.primary
                        )
                    } else {
                        ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                ) {
                    Text("State")
                }
            }
        }

        Divider(modifier = Modifier.padding(vertical = 8.dp))

        when {
            isCrashSelected && crashEvent != null -> {
                CrashDataView(
                    crashEvent = crashEvent,
                    reconstructedStateJson = crashReconstructedState
                )
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
                when (activeTab) {
                    StateViewerTab.DELTA -> {
                        StateSnapshotView(
                            event = selectedEvent,
                            stateJson = selectedEvent.stateDeltaJson,
                            previousStateJson = previousEvent?.stateDeltaJson,
                            showAsDiff = showAsDiff,
                            searchQuery = searchQuery,
                            onSearchQueryChange = { searchQuery = it },
                            label = "Delta"
                        )
                    }
                    StateViewerTab.STATE -> {
                        StateSnapshotView(
                            event = selectedEvent,
                            stateJson = reconstructedState ?: "{}",
                            previousStateJson = previousReconstructedState,
                            showAsDiff = showAsDiff,
                            searchQuery = searchQuery,
                            onSearchQueryChange = { searchQuery = it },
                            label = "Full State"
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StateSnapshotView(
    event: ActionStateEvent,
    stateJson: String,
    previousStateJson: String?,
    showAsDiff: Boolean,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    label: String = "State Data"
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

                if (event.moduleName.isNotBlank()) {
                    Text(
                        text = "Module: ${event.moduleName}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Text(
                    text = "Size: ${stateJson.length} bytes",
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
                    text = label,
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
                        jsonString = stateJson,
                        previousJsonString = previousStateJson,
                        searchQuery = searchQuery,
                        showDiff = showAsDiff && previousStateJson != null,
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
                    .heightIn(max = 300.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
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
                            text = "Parameters",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        IconButton(
                            onClick = {
                                val text = startedEvent.params.entries.joinToString("\n") { "${it.key} = ${it.value}" }
                                copyToClipboard(text)
                            },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                Icons.Default.ContentCopy,
                                contentDescription = "Copy parameters",
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            startedEvent.params.forEach { (key, value) ->
                                Column {
                                    Text(
                                        text = "$key:",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    KotlinObjectTreeViewer(
                                        text = value,
                                        modifier = Modifier.padding(start = 8.dp)
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

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Result",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            IconButton(
                                onClick = { copyToClipboard(result) },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    Icons.Default.ContentCopy,
                                    contentDescription = "Copy result",
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        Surface(
                            color = MaterialTheme.colorScheme.surface,
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.heightIn(max = 200.dp)
                        ) {
                            KotlinObjectTreeViewer(
                                text = result,
                                modifier = Modifier.padding(8.dp)
                            )
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

                    failedEvent.stackTrace?.let { trace ->
                        if (trace.isNotBlank()) {
                            Spacer(modifier = Modifier.height(8.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Stack Trace",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.error
                                )
                                IconButton(
                                    onClick = { copyToClipboard(trace) },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(
                                        Icons.Default.ContentCopy,
                                        contentDescription = "Copy stack trace",
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(4.dp))

                            Surface(
                                color = MaterialTheme.colorScheme.surface,
                                shape = MaterialTheme.shapes.small,
                                modifier = Modifier.heightIn(max = 300.dp)
                            ) {
                                StackTraceView(
                                    stackTrace = trace,
                                    githubBaseUrl = startedEvent?.githubSourceUrl,
                                    modifier = Modifier.padding(8.dp)
                                )
                            }
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

private fun copyToClipboard(text: String) {
    js("navigator.clipboard.writeText(text)")
}

@Composable
private fun CrashDataView(
    crashEvent: CrashEventInfo,
    reconstructedStateJson: String? = null
) {
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

        if (reconstructedStateJson != null) {
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
                        text = "State at Crash Time",
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
                            jsonString = reconstructedStateJson,
                            previousJsonString = null,
                            searchQuery = "",
                            showDiff = false,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
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
                    Box {
                        SelectionContainer {
                            Column(
                                modifier = Modifier
                                    .padding(start = 8.dp, top = 8.dp, bottom = 8.dp, end = 20.dp)
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
                        VerticalScrollbar(
                            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                            adapter = rememberScrollbarAdapter(scrollState)
                        )
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

/**
 * Displays a stack trace with clickable GitHub source links.
 *
 * Only links stack trace lines that belong to the project's package scope,
 * determined from the githubBaseUrl path structure. External library frames
 * (kotlinx, android, java, etc.) are not linked.
 */
@Composable
private fun StackTraceView(
    stackTrace: String,
    githubBaseUrl: String?,
    modifier: Modifier = Modifier
) {
    val githubInfo = remember(githubBaseUrl) {
        extractGitHubInfo(githubBaseUrl)
    }

    val lines = remember(stackTrace) { stackTrace.lines() }
    val scrollState = rememberScrollState()

    Box(modifier = modifier) {
        SelectionContainer {
            Column(
                modifier = Modifier
                    .padding(end = 12.dp)
                    .verticalScroll(scrollState)
            ) {
                lines.forEach { line ->
                    val sourceMatch = remember(line, githubInfo) {
                        if (githubInfo != null) parseStackTraceLine(line, githubInfo.packagePrefix) else null
                    }

                    if (sourceMatch != null && githubInfo != null) {
                        Text(
                            text = line,
                            style = MaterialTheme.typography.bodySmall.copy(
                                textDecoration = TextDecoration.Underline
                            ),
                            color = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.clickable {
                                openInBrowser("${githubInfo.baseUrl}${sourceMatch.first}#L${sourceMatch.second}")
                            }
                        )
                    } else {
                        Text(
                            text = line,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (line.trimStart().startsWith("at "))
                                MaterialTheme.colorScheme.onSurface
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
        VerticalScrollbar(
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
            adapter = rememberScrollbarAdapter(scrollState)
        )
    }
}

private data class GitHubInfo(
    val baseUrl: String,
    val packagePrefix: String
)

/**
 * Extracts GitHub repo base URL and package prefix from a githubSourceUrl.
 *
 * Given a URL like `https://github.com/user/repo/blob/main/app/src/main/kotlin/com/example/MyLogic.kt#L42`,
 * extracts:
 * - baseUrl: `https://github.com/user/repo/blob/main/`
 * - packagePrefix: `com.example` (derived from the path after src/main/kotlin/)
 */
private fun extractGitHubInfo(githubSourceUrl: String?): GitHubInfo? {
    if (githubSourceUrl == null) return null

    val blobIndex = githubSourceUrl.indexOf("/blob/")
    if (blobIndex < 0) return null

    val afterBlob = githubSourceUrl.indexOf("/", blobIndex + 6)
    if (afterBlob < 0) return null

    val nextSlash = githubSourceUrl.indexOf("/", afterBlob + 1)
    if (nextSlash < 0) return null

    val baseUrl = githubSourceUrl.substring(0, nextSlash + 1)

    // Extract the package prefix from the file path
    // Look for common source root patterns: src/main/kotlin/, src/commonMain/kotlin/, etc.
    val pathAfterBranch = githubSourceUrl.substring(nextSlash + 1)
    val kotlinSrcIndex = pathAfterBranch.indexOf("/kotlin/")
    val packagePrefix = if (kotlinSrcIndex >= 0) {
        val packagePath = pathAfterBranch.substring(kotlinSrcIndex + 8)
        val lastSlash = packagePath.lastIndexOf('/')
        if (lastSlash > 0) {
            // Remove filename, keep only package directories
            // Then remove the class name directory (last segment might be a class, keep at least 2 levels)
            val dirs = packagePath.substring(0, lastSlash).split('/')
            if (dirs.size >= 2) {
                dirs.take(dirs.size).joinToString(".")
            } else {
                dirs.joinToString(".")
            }
        } else ""
    } else ""

    if (packagePrefix.isEmpty()) return null

    return GitHubInfo(baseUrl, packagePrefix)
}

/**
 * Parses a stack trace line for source file information.
 * Only matches lines whose package starts with the given packagePrefix.
 * Returns a pair of (relative file path guess, line number) if found.
 */
private fun parseStackTraceLine(line: String, packagePrefix: String): Pair<String, Int>? {
    val trimmed = line.trim()
    if (!trimmed.startsWith("at ")) return null

    val afterAt = trimmed.removePrefix("at ")

    // Only link frames within our project's package scope
    if (!afterAt.startsWith(packagePrefix)) return null

    val parenStart = trimmed.lastIndexOf('(')
    val parenEnd = trimmed.lastIndexOf(')')
    if (parenStart < 0 || parenEnd < 0 || parenEnd <= parenStart) return null

    val fileRef = trimmed.substring(parenStart + 1, parenEnd)
    val colonIndex = fileRef.lastIndexOf(':')
    if (colonIndex < 0) return null

    val fileName = fileRef.substring(0, colonIndex)
    val lineNumber = fileRef.substring(colonIndex + 1).toIntOrNull() ?: return null

    if (!fileName.endsWith(".kt")) return null

    // Derive relative path from the fully qualified class name
    val lastDot = afterAt.lastIndexOf('.')
    if (lastDot < 0) return null
    val beforeMethod = afterAt.substring(0, lastDot)
    val secondLastDot = beforeMethod.lastIndexOf('.')
    val packagePath = if (secondLastDot >= 0) {
        beforeMethod.substring(0, secondLastDot).replace('.', '/')
    } else {
        beforeMethod.replace('.', '/')
    }

    return "$packagePath/$fileName" to lineNumber
}
