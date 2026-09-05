package io.github.syrou.reaktiv.devtools.ui.components

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.syrou.reaktiv.core.tracing.LogicFailureKind
import io.github.syrou.reaktiv.devtools.protocol.DISPATCH_TRACE_CLASS
import io.github.syrou.reaktiv.devtools.protocol.PHASE_TRACE_CLASS
import io.github.syrou.reaktiv.devtools.ui.CrashEventInfo
import io.github.syrou.reaktiv.devtools.ui.DeviceLogRow
import io.github.syrou.reaktiv.devtools.ui.LogicMethodEvent
import io.github.syrou.reaktiv.devtools.ui.NetworkEventRow
import io.github.syrou.reaktiv.devtools.ui.logicCallDepths
import io.github.syrou.reaktiv.introspection.protocol.CapturedAction
import io.github.syrou.reaktiv.introspection.protocol.SessionMarker

private class LogicCall(
    val callId: String,
    val title: String,
    val methodId: String?,
    val timestampMs: Long,
    val durationMs: Long?,
    val failed: Boolean,
    val kind: LogicFailureKind?,
    val depth: Int,
    val searchable: String
) {
    val running: Boolean get() = durationMs == null && kind == null
}

private sealed class StreamRow {
    abstract val timestampMs: Long

    class ActionRow(val event: CapturedAction, val originalIndex: Int) : StreamRow() {
        override val timestampMs: Long get() = event.timestamp
    }

    class CallRow(val call: LogicCall) : StreamRow() {
        override val timestampMs: Long get() = call.timestampMs
    }

    class CrashRow(val info: CrashEventInfo) : StreamRow() {
        override val timestampMs: Long get() = info.timestamp
    }

    class MarkerRow(val marker: SessionMarker) : StreamRow() {
        override val timestampMs: Long get() = marker.timestampMs
    }

    class LogRow(val log: DeviceLogRow) : StreamRow() {
        override val timestampMs: Long get() = log.timestampMs
    }

    class NetworkRow(val row: NetworkEventRow) : StreamRow() {
        override val timestampMs: Long get() = row.event.startedAtMs
    }
}

internal fun networkPathLabel(url: String): String {
    val withoutScheme = url.substringAfter("://", url)
    val path = withoutScheme.substringAfter('/', "")
    return if (path.isBlank()) withoutScheme else "/$path"
}

private fun buildLogicCalls(
    events: List<LogicMethodEvent>,
    callIdToMethodIdentifier: Map<String, String>
): List<LogicCall> {
    val startsByCallId = events.filterIsInstance<LogicMethodEvent.Started>().associateBy { it.callId }
    val completedByCallId = events.filterIsInstance<LogicMethodEvent.Completed>().associateBy { it.callId }
    val failedByCallId = events.filterIsInstance<LogicMethodEvent.Failed>().associateBy { it.callId }

    val depthOf = logicCallDepths(events) { parent ->
        parent.logicClass != DISPATCH_TRACE_CLASS && parent.logicClass != PHASE_TRACE_CLASS
    }

    val callIds = startsByCallId.keys + completedByCallId.keys + failedByCallId.keys
    return callIds.map { callId ->
        val start = startsByCallId[callId]
        val completion = completedByCallId[callId]
        val failure = failedByCallId[callId]
        val methodId = start?.let { "${it.logicClass}.${it.methodName}" }
            ?: callIdToMethodIdentifier[callId]
        val title = methodId?.let {
            val method = it.substringAfterLast('.')
            val owner = it.substringBeforeLast('.').substringAfterLast('.')
            "$owner.$method"
        } ?: callId
        LogicCall(
            callId = callId,
            title = title,
            methodId = methodId,
            timestampMs = start?.timestamp ?: completion?.timestamp ?: failure?.timestamp ?: 0L,
            durationMs = completion?.durationMs ?: failure?.durationMs,
            failed = failure?.kind == LogicFailureKind.THROWN,
            kind = failure?.kind,
            depth = depthOf(callId).coerceAtMost(3),
            searchable = buildString {
                append(methodId ?: "")
                start?.params?.values?.forEach { append(' ').append(it) }
                completion?.result?.let { append(' ').append(it) }
                failure?.exceptionType?.let { append(' ').append(it) }
            }
        )
    }
}

private val ROW_HEIGHT = 20.dp

@Composable
internal fun ActionStream(
    dataRevision: Long,
    actions: List<CapturedAction>,
    logicMethodEvents: List<LogicMethodEvent> = emptyList(),
    crashEvent: CrashEventInfo? = null,
    markers: List<SessionMarker> = emptyList(),
    deviceLogs: List<DeviceLogRow> = emptyList(),
    networkEvents: List<NetworkEventRow> = emptyList(),
    selectedIndex: Int? = null,
    selectedLogicMethodCallId: String? = null,
    selectedNetworkRequestId: String? = null,
    crashSelected: Boolean = false,
    followLatest: Boolean = true,
    newEventsWhilePaused: Int = 0,
    excludedActionTypes: Set<String>,
    excludedLogicMethods: Set<String> = emptySet(),
    callIdToMethodIdentifier: Map<String, String> = emptyMap(),
    showActions: Boolean = true,
    showLogicMethods: Boolean = true,
    showLogs: Boolean = false,
    showNetwork: Boolean = true,
    searchQuery: String = "",
    onClearSearch: () -> Unit = {},
    searchField: (@Composable () -> Unit)? = null,
    onSelectAction: (Int?) -> Unit = {},
    onSelectLogicMethod: (String?) -> Unit = {},
    onSelectNetworkRequest: (String?) -> Unit = {},
    onSelectCrash: (Boolean) -> Unit = {},
    onMarkerClick: (SessionMarker) -> Unit = {},
    onFollowLatest: () -> Unit = {},
    onAddExclusion: (String) -> Unit = {},
    onRemoveExclusion: (String) -> Unit = {},
    onSetExclusions: (Set<String>) -> Unit = {},
    onAddLogicMethodExclusion: (String) -> Unit = {},
    onRemoveLogicMethodExclusion: (String) -> Unit = {},
    onToggleShowActions: () -> Unit = {},
    onToggleShowLogicMethods: () -> Unit = {},
    onToggleShowLogs: () -> Unit = {},
    onToggleShowNetwork: () -> Unit = {},
    onClear: () -> Unit
) {
    val listState = rememberLazyListState()
    var showFilters by remember { mutableStateOf(false) }
    var exclusionInput by remember { mutableStateOf("") }


    val rows = remember(
        actions.size, logicMethodEvents.size, crashEvent, markers.size, deviceLogs.size,
        networkEvents.size, excludedActionTypes, excludedLogicMethods, showActions,
        showLogicMethods, showLogs, showNetwork, searchQuery, callIdToMethodIdentifier.size
    ) {
        val query = searchQuery.trim()
        fun matches(text: String): Boolean = query.isEmpty() || text.contains(query, ignoreCase = true)
        buildList {
            if (showActions) {
                actions.forEachIndexed { index, event ->
                    if (event.actionType in excludedActionTypes) return@forEachIndexed
                    if (!matches("${event.actionType} ${event.moduleName} ${event.actionData}")) {
                        return@forEachIndexed
                    }
                    add(StreamRow.ActionRow(event, index))
                }
            }
            if (showLogicMethods) {
                buildLogicCalls(logicMethodEvents, callIdToMethodIdentifier).forEach { call ->
                    val hidden = call.methodId?.let {
                        it.startsWith("$DISPATCH_TRACE_CLASS.") || it.startsWith("DispatchPhase.")
                    } == true
                    if (hidden) return@forEach
                    if (call.methodId != null && call.methodId in excludedLogicMethods) return@forEach
                    if (!matches(call.searchable)) return@forEach
                    add(StreamRow.CallRow(call))
                }
            }
            if (showLogs) {
                deviceLogs.forEach { log ->
                    if (!matches("${log.level} ${log.category} ${log.message}")) return@forEach
                    add(StreamRow.LogRow(log))
                }
            }
            if (showNetwork) {
                networkEvents.forEach { row ->
                    val event = row.event
                    if (!matches("${event.method} ${event.url} ${event.responseStatus ?: ""} ${event.error ?: ""}")) {
                        return@forEach
                    }
                    add(StreamRow.NetworkRow(row))
                }
            }
            markers.forEach { marker ->
                if (matches("${marker.label} ${marker.note}")) add(StreamRow.MarkerRow(marker))
            }
            crashEvent?.let { add(StreamRow.CrashRow(it)) }
        }.sortedByDescending { it.timestampMs }
    }

    LaunchedEffect(rows.size, followLatest) {
        if (followLatest && rows.isNotEmpty()) {
            listState.animateScrollToItem(0)
        }
    }
    LaunchedEffect(selectedLogicMethodCallId) {
        val id = selectedLogicMethodCallId ?: return@LaunchedEffect
        val index = rows.indexOfFirst { it is StreamRow.CallRow && it.call.callId == id }
        if (index >= 0) listState.animateScrollToItem(index)
    }
    LaunchedEffect(selectedNetworkRequestId) {
        val id = selectedNetworkRequestId ?: return@LaunchedEffect
        val index = rows.indexOfFirst { it is StreamRow.NetworkRow && it.row.event.id == id }
        if (index >= 0) listState.animateScrollToItem(index)
    }
    LaunchedEffect(selectedIndex) {
        if (followLatest) return@LaunchedEffect
        val target = selectedIndex ?: return@LaunchedEffect
        val index = rows.indexOfFirst { it is StreamRow.ActionRow && it.originalIndex == target }
        if (index >= 0) listState.animateScrollToItem(index)
    }

    val filtersActive = excludedActionTypes.isNotEmpty() || excludedLogicMethods.isNotEmpty() ||
        !showActions || !showLogicMethods || showLogs || !showNetwork

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Column {
                Text(text = "Stream", style = MaterialTheme.typography.titleSmall)
                Text(
                    text = "${rows.size} events",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            searchField?.invoke()
            Spacer(modifier = Modifier.weight(1f))
            FollowChip(
                following = followLatest,
                newEvents = newEventsWhilePaused,
                onFollow = onFollowLatest
            )
            Box {
                IconButton(onClick = { showFilters = true }) {
                    Icon(
                        imageVector = Icons.Default.FilterList,
                        contentDescription = "Filters",
                        tint = if (filtersActive) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface
                    )
                }
                DropdownMenu(
                    expanded = showFilters,
                    onDismissRequest = { showFilters = false },
                    modifier = Modifier.width(340.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            LaneChip("Actions", actions.size, showActions, onToggleShowActions)
                            LaneChip("Logic", null, showLogicMethods, onToggleShowLogicMethods)
                            LaneChip("Logs", deviceLogs.size, showLogs, onToggleShowLogs)
                            LaneChip("Network", networkEvents.size, showNetwork, onToggleShowNetwork)
                        }
                        OutlinedTextField(
                            value = exclusionInput,
                            onValueChange = { exclusionInput = it },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("Exclude action types, comma separated") },
                            singleLine = true,
                            trailingIcon = {
                                if (exclusionInput.isNotBlank()) {
                                    TextButton(onClick = {
                                        val additions = exclusionInput.split(",")
                                            .map { it.trim() }
                                            .filter { it.isNotBlank() }
                                            .toSet()
                                        onSetExclusions(excludedActionTypes + additions)
                                        exclusionInput = ""
                                    }) { Text("Add") }
                                }
                            }
                        )
                        if (excludedActionTypes.isNotEmpty()) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                excludedActionTypes.forEach { actionType ->
                                    FilterChip(
                                        selected = true,
                                        onClick = { onRemoveExclusion(actionType) },
                                        label = {
                                            Text("$actionType  x", style = MaterialTheme.typography.labelSmall)
                                        }
                                    )
                                }
                            }
                        }
                        if (excludedLogicMethods.isNotEmpty()) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                excludedLogicMethods.forEach { methodId ->
                                    FilterChip(
                                        selected = true,
                                        onClick = { onRemoveLogicMethodExclusion(methodId) },
                                        label = {
                                            Text("$methodId  x", style = MaterialTheme.typography.labelSmall)
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
            if (actions.isNotEmpty() || logicMethodEvents.isNotEmpty()) {
                IconButton(onClick = onClear) {
                    Icon(Icons.Default.Delete, contentDescription = "Clear history")
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        StreamHeader()

        val capturedCount = actions.size + logicMethodEvents.size + deviceLogs.size +
            networkEvents.size + (if (crashEvent != null) 1 else 0)

        if (rows.isEmpty()) {
            if (capturedCount == 0) {
                EmptyState(
                    title = "No actions yet",
                    detail = "The device is connected and publishing. Interact with the app and " +
                        "everything it dispatches arrives here."
                )
            } else {
                FilteredEmptyState(
                    query = searchQuery,
                    hiddenCount = capturedCount,
                    onClearFilters = {
                        onClearSearch()
                        onSetExclusions(emptySet())
                        excludedLogicMethods.forEach(onRemoveLogicMethodExclusion)
                        if (!showActions) onToggleShowActions()
                        if (!showLogicMethods) onToggleShowLogicMethods()
                        if (!showNetwork) onToggleShowNetwork()
                    }
                )
            }
        } else {
            Box(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    itemsIndexed(rows) { _, row ->
                        when (row) {
                            is StreamRow.ActionRow -> ActionRowView(
                                row = row,
                                selected = row.originalIndex == selectedIndex,
                                onClick = { onSelectAction(row.originalIndex) },
                                onExclude = { onAddExclusion(row.event.actionType) }
                            )
                            is StreamRow.CallRow -> CallRowView(
                                call = row.call,
                                selected = row.call.callId == selectedLogicMethodCallId,
                                onClick = { onSelectLogicMethod(row.call.callId) },
                                onExclude = { row.call.methodId?.let(onAddLogicMethodExclusion) }
                            )
                            is StreamRow.CrashRow -> CrashRowView(
                                info = row.info,
                                selected = crashSelected,
                                onClick = { onSelectCrash(true) }
                            )
                            is StreamRow.MarkerRow -> MarkerRowView(
                                marker = row.marker,
                                onClick = { onMarkerClick(row.marker) }
                            )
                            is StreamRow.LogRow -> LogRowView(row.log)
                            is StreamRow.NetworkRow -> NetworkRowView(
                                row = row.row,
                                selected = row.row.event.id == selectedNetworkRequestId,
                                onClick = { onSelectNetworkRequest(row.row.event.id) }
                            )
                        }
                    }
                }
                VerticalScrollbar(
                    adapter = rememberScrollbarAdapter(listState),
                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight()
                )
            }
        }
    }
}

@Composable
private fun FollowChip(following: Boolean, newEvents: Int, onFollow: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val label = when {
        following -> "Following"
        newEvents == 1 -> "1 new"
        newEvents > 1 -> "$newEvents new"
        else -> "Follow"
    }

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (following) colors.surfaceVariant.copy(alpha = 0.5f) else colors.primaryContainer
            )
            .let { base -> if (following) base else base.clickable(onClick = onFollow) }
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(if (following) colors.primary else colors.onPrimaryContainer)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (following) colors.onSurfaceVariant else colors.onPrimaryContainer,
            maxLines = 1
        )
    }
}

@Composable
private fun RowShell(
    barColor: Color,
    selected: Boolean,
    indent: Int,
    onClick: (() -> Unit)?,
    content: @Composable (Boolean) -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = (indent * 12).dp)
            .height(ROW_HEIGHT)
            .clip(RoundedCornerShape(3.dp))
            .background(
                when {
                    selected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                    hovered -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    else -> MaterialTheme.colorScheme.surface
                }
            )
            .hoverable(interaction)
            .let { base -> onClick?.let { base.clickable(onClick = it) } ?: base },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .width(if (selected) 3.dp else 2.dp)
                .fillMaxHeight()
                .background(barColor)
        )
        Box(modifier = Modifier.weight(1f).padding(horizontal = 8.dp)) {
            content(hovered)
        }
    }
}


private val STATUS_WIDTH = 52.dp
private val DURATION_WIDTH = 62.dp
private val OFFSET_WIDTH = 88.dp
private val MUTE_WIDTH = 46.dp

@Composable
private fun StreamHeader() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(16.dp)
            .padding(start = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.width(2.dp))
        Box(modifier = Modifier.weight(1f).padding(horizontal = 8.dp)) {
            HeaderLabel("Event")
        }
        MetaCell(STATUS_WIDTH) { HeaderLabel("Status") }
        MetaCell(DURATION_WIDTH) { HeaderLabel("Took") }
        MetaCell(OFFSET_WIDTH) { HeaderLabel("At") }
        MetaCell(MUTE_WIDTH) { }
    }
    Divider(
        modifier = Modifier.fillMaxWidth().height(1.dp),
        color = MaterialTheme.colorScheme.outlineVariant
    )
}

@Composable
private fun HeaderLabel(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        softWrap = false
    )
}

@Composable
private fun MetaCell(width: Dp, content: @Composable () -> Unit) {
    Box(modifier = Modifier.width(width), contentAlignment = Alignment.CenterEnd) {
        content()
    }
}

@Composable
private fun RowTail(
    timestampMs: Long,
    statusText: String? = null,
    statusColor: Color? = null,
    durationText: String? = null,
    hovered: Boolean = false,
    onExclude: (() -> Unit)? = null
) {
    MetaCell(STATUS_WIDTH) {
        if (statusText != null) {
            Text(
                text = statusText,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = statusColor ?: MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                softWrap = false
            )
        }
    }
    MetaCell(DURATION_WIDTH) {
        if (durationText != null) {
            MetaText(durationText)
        }
    }
    MetaCell(OFFSET_WIDTH) {
        MetaText(formatClockTime(timestampMs))
    }
    MetaCell(MUTE_WIDTH) {
        if (hovered && onExclude != null) {
            Text(
                text = "mute",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier
                    .clip(RoundedCornerShape(3.dp))
                    .clickable(onClick = onExclude)
                    .padding(horizontal = 4.dp, vertical = 1.dp)
            )
        }
    }
}

@Composable
private fun MetaText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis
    )
}

@Composable
private fun ActionRowView(
    row: StreamRow.ActionRow,
    selected: Boolean,
    onClick: () -> Unit,
    onExclude: () -> Unit
) {
    RowShell(MaterialTheme.colorScheme.primary, selected, indent = 0, onClick = onClick) { hovered ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = row.event.actionType,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (row.event.moduleName.isNotBlank()) {
                Spacer(modifier = Modifier.width(6.dp))
                MetaText(row.event.moduleName.substringAfterLast('.'))
            }
            Spacer(modifier = Modifier.weight(1f))
            RowTail(
                timestampMs = row.event.timestamp,
                hovered = hovered,
                onExclude = onExclude
            )
        }
    }
}

@Composable
private fun CallRowView(
    call: LogicCall,
    selected: Boolean,
    onClick: () -> Unit,
    onExclude: () -> Unit
) {
    val color = when (call.kind) {
        LogicFailureKind.THROWN -> MaterialTheme.colorScheme.error
        LogicFailureKind.CANCELLED -> MaterialTheme.colorScheme.tertiary
        LogicFailureKind.SCOPE_DISPOSED -> MaterialTheme.colorScheme.outline
        null -> if (call.running) {
            MaterialTheme.colorScheme.tertiary
        } else {
            MaterialTheme.colorScheme.secondary
        }
    }
    RowShell(color, selected, indent = call.depth, onClick = onClick) { hovered ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = call.title,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.weight(1f))
            RowTail(
                timestampMs = call.timestampMs,
                statusText = when (call.kind) {
                    LogicFailureKind.THROWN -> "failed"
                    LogicFailureKind.CANCELLED -> "cancelled"
                    LogicFailureKind.SCOPE_DISPOSED -> "scope gone"
                    null -> if (call.running) "running" else null
                },
                statusColor = when (call.kind) {
                    LogicFailureKind.THROWN -> MaterialTheme.colorScheme.error
                    LogicFailureKind.CANCELLED -> MaterialTheme.colorScheme.tertiary
                    LogicFailureKind.SCOPE_DISPOSED -> MaterialTheme.colorScheme.outline
                    null -> null
                },
                durationText = if (call.failed || call.running) null else formatDuration(call.durationMs ?: 0L),
                hovered = hovered,
                onExclude = onExclude
            )
        }
    }
}

@Composable
private fun CrashRowView(
    info: CrashEventInfo,
    selected: Boolean,
    onClick: () -> Unit
) {
    RowShell(MaterialTheme.colorScheme.error, selected, indent = 0, onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Crash: ${info.exception.exceptionType}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            info.exception.message?.let {
                Spacer(modifier = Modifier.width(6.dp))
                MetaText(it)
            }
            Spacer(modifier = Modifier.weight(1f))
            RowTail(timestampMs = info.timestamp)
        }
    }
}

@Composable
private fun MarkerRowView(
    marker: SessionMarker,
    onClick: () -> Unit
) {
    RowShell(MaterialTheme.colorScheme.tertiary, selected = false, indent = 0, onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.Flag,
                contentDescription = "Marker",
                tint = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = marker.label,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (marker.note.isNotBlank()) {
                Spacer(modifier = Modifier.width(6.dp))
                MetaText(marker.note)
            }
            Spacer(modifier = Modifier.weight(1f))
            RowTail(timestampMs = marker.timestampMs)
        }
    }
}

@Composable
private fun NetworkRowView(
    row: NetworkEventRow,
    selected: Boolean,
    onClick: () -> Unit
) {
    val event = row.event
    val barColor = when {
        event.error != null -> MaterialTheme.colorScheme.error
        (event.responseStatus ?: 0) >= 400 -> MaterialTheme.colorScheme.error
        (event.responseStatus ?: 0) >= 300 -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.secondary
    }
    RowShell(barColor, selected, indent = 0, onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = event.method,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.secondary
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = networkPathLabel(event.url),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.weight(1f))
            RowTail(
                timestampMs = event.startedAtMs,
                statusText = when {
                    event.error != null -> "failed"
                    event.responseStatus != null -> "${event.responseStatus}"
                    else -> "pending"
                },
                statusColor = barColor,
                durationText = formatDuration(event.durationMs)
            )
        }
    }
}

@Composable
private fun LogRowView(log: DeviceLogRow) {
    val barColor = when (log.level) {
        "ERROR" -> MaterialTheme.colorScheme.error
        "WARN" -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.outline
    }
    RowShell(barColor, selected = false, indent = 0, onClick = null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "[${log.category}] ${log.message}",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = if (log.level == "ERROR") MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            RowTail(timestampMs = log.timestampMs)
        }
    }
}

@Composable
private fun LaneChip(label: String, count: Int?, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = {
            Text(
                text = if (count == null) label else "$label $count",
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    )
}
