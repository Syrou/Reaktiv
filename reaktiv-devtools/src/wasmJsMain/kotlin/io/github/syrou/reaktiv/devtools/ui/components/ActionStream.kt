package io.github.syrou.reaktiv.devtools.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import io.github.syrou.reaktiv.devtools.ui.ActionStateEvent
import io.github.syrou.reaktiv.devtools.ui.CrashEventInfo
import io.github.syrou.reaktiv.devtools.ui.LogicMethodEvent
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * Unified event type for the event stream, combining actions, logic method events, and crashes.
 */
sealed class StreamEvent {
    abstract val timestamp: Long
    abstract val clientId: String

    data class Action(val event: ActionStateEvent, val originalIndex: Int) : StreamEvent() {
        override val timestamp: Long = event.timestamp
        override val clientId: String = event.clientId
    }

    data class LogicMethod(val event: LogicMethodEvent) : StreamEvent() {
        override val timestamp: Long = event.timestamp
        override val clientId: String = event.clientId
    }

    data class Crash(val event: CrashEventInfo) : StreamEvent() {
        override val timestamp: Long = event.timestamp
        override val clientId: String = event.clientId
    }
}

/**
 * Displays a stream of actions and logic method events from publisher clients.
 */
@Composable
fun ActionStream(
    actions: List<ActionStateEvent>,
    logicMethodEvents: List<LogicMethodEvent> = emptyList(),
    crashEvent: CrashEventInfo? = null,
    selectedIndex: Int? = null,
    selectedLogicMethodCallId: String? = null,
    autoSelectLatest: Boolean = true,
    excludedActionTypes: Set<String>,
    timeTravelEnabled: Boolean = false,
    showActions: Boolean = true,
    showLogicMethods: Boolean = true,
    onSelectAction: (Int?) -> Unit = {},
    onSelectLogicMethod: (String?) -> Unit = {},
    onToggleAutoSelect: () -> Unit = {},
    onAddExclusion: (String) -> Unit = {},
    onRemoveExclusion: (String) -> Unit = {},
    onSetExclusions: (Set<String>) -> Unit = {},
    onToggleTimeTravel: () -> Unit = {},
    onToggleShowActions: () -> Unit = {},
    onToggleShowLogicMethods: () -> Unit = {},
    onClear: () -> Unit
) {
    val listState = rememberLazyListState()
    var exclusionInput by remember { mutableStateOf("") }

    // Build combined event stream
    val streamEvents = remember(actions, logicMethodEvents, crashEvent, excludedActionTypes, showActions, showLogicMethods) {
        buildList {
            if (showActions) {
                actions.forEachIndexed { index, action ->
                    if (!excludedActionTypes.contains(action.actionType)) {
                        add(StreamEvent.Action(action, index))
                    }
                }
            }
            if (showLogicMethods) {
                logicMethodEvents.forEach { event ->
                    add(StreamEvent.LogicMethod(event))
                }
            }
            if (crashEvent != null) {
                add(StreamEvent.Crash(crashEvent))
            }
        }.sortedByDescending { it.timestamp }
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
                text = "Event Stream (${streamEvents.size})",
                style = MaterialTheme.typography.titleMedium
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (actions.isNotEmpty()) {
                    IconButton(onClick = onToggleTimeTravel) {
                        Icon(
                            imageVector = Icons.Default.History,
                            contentDescription = if (timeTravelEnabled) "Exit Time Travel" else "Time Travel",
                            tint = if (timeTravelEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                TextButton(onClick = onToggleAutoSelect) {
                    Text(if (autoSelectLatest) "Auto: ON" else "Auto: OFF")
                }

                if (actions.isNotEmpty() || logicMethodEvents.isNotEmpty()) {
                    TextButton(onClick = onClear) {
                        Text("Clear")
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Event type filter toggles
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = showActions,
                onClick = onToggleShowActions,
                label = { Text("Actions (${actions.size})") }
            )
            FilterChip(
                selected = showLogicMethods,
                onClick = onToggleShowLogicMethods,
                label = { Text("Logic Methods (${logicMethodEvents.size})") }
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

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

        Divider(modifier = Modifier.padding(vertical = 8.dp))

        if (streamEvents.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(32.dp)
            ) {
                Text(
                    text = "No events yet",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = if (timeTravelEnabled) {
                    PaddingValues(bottom = 80.dp)
                } else {
                    PaddingValues(0.dp)
                }
            ) {
                items(streamEvents.size) { index ->
                    when (val event = streamEvents[index]) {
                        is StreamEvent.Action -> {
                            ActionEventCard(
                                action = event.event,
                                isSelected = event.originalIndex == selectedIndex,
                                onClick = { onSelectAction(event.originalIndex) },
                                onExclude = { onAddExclusion(event.event.actionType) }
                            )
                        }
                        is StreamEvent.LogicMethod -> {
                            val startedEvent = logicMethodEvents
                                .filterIsInstance<LogicMethodEvent.Started>()
                                .find { it.callId == event.event.callId }
                            LogicMethodEventCard(
                                event = event.event,
                                startedEvent = startedEvent,
                                isSelected = event.event.callId == selectedLogicMethodCallId,
                                onClick = { onSelectLogicMethod(event.event.callId) }
                            )
                        }
                        is StreamEvent.Crash -> {
                            CrashEventCard(
                                crashEvent = event.event,
                                isSelected = false,
                                onClick = { }
                            )
                        }
                    }
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

@Composable
private fun LogicMethodEventCard(
    event: LogicMethodEvent,
    startedEvent: LogicMethodEvent.Started? = null,
    isSelected: Boolean = false,
    onClick: () -> Unit = {}
) {
    val (eventType, eventColor) = when (event) {
        is LogicMethodEvent.Started -> Pair("STARTED", MaterialTheme.colorScheme.tertiary)
        is LogicMethodEvent.Completed -> Pair("COMPLETED", MaterialTheme.colorScheme.primary)
        is LogicMethodEvent.Failed -> Pair("FAILED", MaterialTheme.colorScheme.error)
    }

    val logicClassName = when (event) {
        is LogicMethodEvent.Started -> event.logicClass
        else -> startedEvent?.logicClass
    }

    val methodName = when (event) {
        is LogicMethodEvent.Started -> event.methodName
        else -> startedEvent?.methodName
    }

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
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Surface(
                        color = eventColor.copy(alpha = 0.2f),
                        shape = MaterialTheme.shapes.small
                    ) {
                        Text(
                            text = eventType,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = eventColor
                        )
                    }
                    Text(
                        text = buildString {
                            if (logicClassName != null) {
                                append(logicClassName.substringAfterLast('.'))
                                append(".")
                            }
                            append(methodName ?: "Unknown")
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Text(
                    text = formatTimestamp(event.timestamp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "Client: ${event.clientId}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun formatTimestamp(timestamp: Long): String {
    val instant = Instant.fromEpochMilliseconds(timestamp)
    val dateTime = instant.toLocalDateTime(TimeZone.currentSystemDefault())
    val millis = (timestamp % 1000).toString().padStart(3, '0')

    return "${dateTime.hour.toString().padStart(2, '0')}:${
        dateTime.minute.toString().padStart(2, '0')
    }:${dateTime.second.toString().padStart(2, '0')}:${millis}"
}
