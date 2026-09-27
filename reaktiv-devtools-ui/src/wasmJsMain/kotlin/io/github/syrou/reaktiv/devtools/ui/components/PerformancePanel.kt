package io.github.syrou.reaktiv.devtools.ui.components

import io.github.syrou.reaktiv.devtools.ui.kind
import io.github.syrou.reaktiv.devtools.protocol.kind
import io.github.syrou.reaktiv.devtools.protocol.SpanKind
import io.github.syrou.reaktiv.devtools.ui.MethodSort
import io.github.syrou.reaktiv.devtools.ui.ViewChoices
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import io.github.syrou.reaktiv.devtools.protocol.DispatchStats
import io.github.syrou.reaktiv.devtools.protocol.MethodStats
import io.github.syrou.reaktiv.devtools.protocol.PERFORMANCE_FINDING_CATEGORIES
import io.github.syrou.reaktiv.devtools.protocol.SYNTHETIC_TRACE_CLASSES
import io.github.syrou.reaktiv.devtools.protocol.asClipboardText
import io.github.syrou.reaktiv.devtools.protocol.Finding
import io.github.syrou.reaktiv.devtools.protocol.GUARD_TRACE_CLASS
import io.github.syrou.reaktiv.devtools.protocol.ModuleSizeStats
import io.github.syrou.reaktiv.devtools.protocol.StateSizeTracker
import io.github.syrou.reaktiv.devtools.protocol.ThreadStats
import io.github.syrou.reaktiv.devtools.protocol.aggregateDispatchStats
import io.github.syrou.reaktiv.devtools.protocol.aggregateLogicStats
import io.github.syrou.reaktiv.devtools.protocol.aggregateThreadStats
import io.github.syrou.reaktiv.devtools.ui.LogicMethodEvent
import io.github.syrou.reaktiv.devtools.ui.LogicTrace
import io.github.syrou.reaktiv.introspection.protocol.CapturedAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.hoverable

@Composable
internal fun PerformancePanel(
    view: ViewChoices = ViewChoices(),
    onViewChange: (ViewChoices) -> Unit = {},
    logicMethodEvents: List<LogicMethodEvent>,
    findings: List<Finding>,
    searchQuery: String = "",
    searchField: @Composable () -> Unit = {},
    actionStateHistory: List<CapturedAction> = emptyList(),
    initialStateJson: String = "{}",
) {
    val trace = LogicTrace.of(logicMethodEvents)
    val started = trace.started
    val completed = trace.completed
    val failed = trace.failed
    val stats = remember(started, completed, failed) { aggregateLogicStats(started, completed, failed) }
    val threadStats = remember(started, completed, failed) { aggregateThreadStats(started, completed, failed) }
    val dispatchStats = remember(started, completed, failed) { aggregateDispatchStats(started, completed, failed) }
    val maxTotal = stats.maxOfOrNull { it.totalMs } ?: 0L

    val warnings = remember(findings) { findings.filter { it.category in PERFORMANCE_FINDING_CATEGORIES } }

    val sizeTracker = remember(initialStateJson) {
        StateSizeTracker().also { it.feedInitial(initialStateJson) }
    }
    val sizeStats = remember(actionStateHistory, initialStateJson) {
        val tracker = if (actionStateHistory.size < sizeTracker.processed) {
            StateSizeTracker().also { it.feedInitial(initialStateJson) }
        } else {
            sizeTracker
        }
        for (i in tracker.processed until actionStateHistory.size) {
            tracker.feed(actionStateHistory[i])
        }
        tracker.snapshot()
    }

    var sortBy by storeBacked(view.methodSort) { onViewChange(view.copy(methodSort = it)) }
    var warningsExpanded by storeBacked(view.performanceWarningsOpen) { onViewChange(view.copy(performanceWarningsOpen = it)) }
    var showPipeline by storeBacked(view.performancePipelineShown) { onViewChange(view.copy(performancePipelineShown = it)) }
    val pipelineCount = stats.count { it.kind.pipeline }
    val shownStats = stats
        .filter { searchQuery.isBlank() || it.methodIdentifier.contains(searchQuery, ignoreCase = true) }
        .filter { showPipeline || !it.kind.pipeline }

    if (logicMethodEvents.isEmpty()) {
        EmptyState(
            title = "No timings captured",
            detail = "Method timings come from the tracing compiler plugin. Set " +
                "reaktivTracing.enabled for this build and reconnect, and the methods your " +
                "logic runs appear here with their durations."
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Performance",
                style = MaterialTheme.typography.titleMedium
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                searchField()
                Text(
                    text = "${shownStats.size} of ${stats.size} methods, ${shownStats.sumOf { it.calls }} calls",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                FilterChip(
                    selected = warningsExpanded,
                    onClick = { warningsExpanded = !warningsExpanded },
                    label = {
                        Text("Warnings ${warnings.size}", style = MaterialTheme.typography.labelSmall)
                    }
                )
                CopyControl(
                    actions = listOf(
                        CopyAction("all warnings") {
                            warnings.joinToString(separator = "\n") { it.asClipboardText() }
                        }
                    ),
                    enabled = warnings.isNotEmpty()
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilterChip(
                selected = showPipeline,
                enabled = pipelineCount > 0,
                onClick = { showPipeline = !showPipeline },
                label = { Text("Pipeline $pipelineCount", style = MaterialTheme.typography.labelSmall) }
            )
            CopyControl(
                actions = listOf(
                    CopyAction("method stats") {
                        displayedStatsText(shownStats)
                    }
                )
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        val showWarnings = warningsExpanded
        val hasAnyWarning = warnings.isNotEmpty()

        val visibleSizeStats = sizeStats
        val visibleThreadStats = threadStats
        val showDispatch = dispatchStats != null
        val displayedStats = shownStats
            .let { list ->
                when (sortBy) {
                    MethodSort.TOTAL -> list.sortedByDescending { it.totalMs }
                    MethodSort.MAX -> list.sortedByDescending { it.maxMs }
                    MethodSort.AVG -> list.sortedByDescending { it.avgMs }
                    MethodSort.CALLS -> list.sortedByDescending { it.calls }
                }
            }

        Box(modifier = Modifier.fillMaxSize()) {
            val listState = rememberLazyListState()
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize()
            ) {
                if (showWarnings && hasAnyWarning) {
                    item(key = "warning-banner") {
                        SummarySlot { PerformanceWarnings(warnings = warnings, methodStats = stats) }
                    }
                }
                if (showDispatch && dispatchStats != null) {
                    item(key = "dispatch-summary") {
                        SummarySlot { DispatchSummary(dispatchStats) }
                    }
                }
                if (visibleThreadStats.isNotEmpty()) {
                    item(key = "thread-summary") {
                        SummarySlot { ThreadSummary(visibleThreadStats) }
                    }
                }
                if (visibleSizeStats.isNotEmpty()) {
                    item(key = "state-size-summary") {
                        SummarySlot { StateSizeSummary(visibleSizeStats, suppressWarnings = false) }
                    }
                }
                if (displayedStats.isEmpty()) {
                    item(key = "empty-message") {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "No logic trace events captured yet",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    item(key = "method-header") {
                        MethodTableHeader(sortBy = sortBy, onSort = { sortBy = it })
                    }
                    items(displayedStats, key = { it.methodIdentifier }) { stat ->
                        MethodStatsRow(stat = stat, maxTotal = maxTotal)
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

@Composable
private fun DispatchSummary(stats: DispatchStats) {
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
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = "Dispatch queue",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                StatLabel("actions", stats.processedActions.toString())
                StatLabel("avg wait", formatDuration(stats.avgQueueWaitMs))
                Text(
                    text = "max wait ${formatDuration(stats.maxQueueWaitMs)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (stats.isCongested) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
                StatLabel("max depth", stats.maxQueueDepth.toString())
                StatLabel("reducer time", formatDuration(stats.totalProcessMs))
            }
        }
    }
}

@Composable
private fun StateSizeSummary(sizeStats: List<ModuleSizeStats>, suppressWarnings: Boolean) {
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
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = "State size",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )
            sizeStats.forEach { stat ->
                val warn = !suppressWarnings && stat.isSuspicious
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = stat.shortName,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (warn) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            text = formatBytes(stat.currentBytes),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "max ${formatBytes(stat.maxBytes)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = if (stat.growthPercent >= 0) "+${stat.growthPercent}%" else "${stat.growthPercent}%",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (warn) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PerformanceWarnings(warnings: List<Finding>, methodStats: List<MethodStats>) {
    val byId = remember(methodStats) { methodStats.associateBy { it.methodIdentifier } }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            SelectionContainer {
                Text(
                    text = "Performance warnings",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error
                )
            }
            warnings.forEach { finding ->
                val advice = finding.advice
                WarningRow(
                    text = "${finding.title}: ${finding.detail}",
                    meaning = advice?.meaning.orEmpty(),
                    impact = advice?.impact.orEmpty(),
                    fix = advice?.fix.orEmpty(),
                    culprits = finding.culprits.mapNotNull { byId[it] },
                    culpritLabel = "Open",
                    note = if (finding.category == "stall" && finding.stacks.isEmpty()) NO_STALL_STACK_NOTE else null,
                    stackBlocks = finding.stacks.map { StackBlock(it.label, it.stack) }
                )
            }
        }
    }
}

private const val NO_STALL_STACK_NOTE: String =
    "The main thread stack could not be captured on this platform, so the exact blocker is not shown here. " +
        "The freeze is real: look for blocking work on Main in a Composable, a reducer, or synchronous " +
        "IO/serialization."

@Composable
private fun CulpritLinks(label: String, culprits: List<MethodStats>) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "$label:",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onErrorContainer
        )
        FlowLocationChips(culprits)
    }
}

@Composable
private fun FlowLocationChips(culprits: List<MethodStats>) {
    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
        culprits.forEach { stat ->
            val label = stat.location?.let { "${stat.methodIdentifier} ($it)" } ?: stat.methodIdentifier
            val url = stat.githubSourceUrl
            if (url != null) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    textDecoration = TextDecoration.Underline,
                    modifier = Modifier.clickable { openInBrowser(url) }
                )
            } else {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
    }
}

private data class StackBlock(val label: String, val stack: String)

@Composable
private fun WarningRow(
    text: String,
    meaning: String,
    impact: String,
    fix: String,
    culprits: List<MethodStats> = emptyList(),
    culpritLabel: String? = null,
    note: String? = null,
    stackBlocks: List<StackBlock> = emptyList()
) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top
        ) {
            SelectionContainer(modifier = Modifier.weight(1f)) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
            Text(
                text = if (expanded) "Hide" else "Hint",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.clickable { expanded = !expanded }
            )
        }
        if (culpritLabel != null && culprits.isNotEmpty()) {
            CulpritLinks(culpritLabel, culprits)
        }
        stackBlocks.forEachIndexed { index, block ->
            StackTraceBlock(
                label = if (stackBlocks.size > 1) "Freeze ${index + 1}: ${block.label}" else block.label,
                stack = block.stack
            )
        }
        if (note != null) {
            SelectionContainer(modifier = Modifier.padding(start = 8.dp)) {
                Text(
                    text = note,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
        if (expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 8.dp, top = 2.dp, bottom = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                HintSection("What this means", meaning)
                HintSection("Why it matters", impact)
                HintSection("How to fix in Reaktiv", fix)
            }
        }
    }
}

@Composable
private fun HintSection(label: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.error
        )
        SelectionContainer {
            Text(
                text = body,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }
    }
}

@Composable
private fun StackTraceBlock(label: String, stack: String) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(start = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.error
        )
        Surface(
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth()
        ) {
            SelectionContainer {
                Text(
                    text = stack,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 220.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(8.dp)
                )
            }
        }
    }
}

@Composable
private fun ThreadSummary(threadStats: List<ThreadStats>) {
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
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = "Threads",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )
            threadStats.forEach { stat ->
                Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = stat.thread,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (stat.isMain && stat.busyMs > 0) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            }
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(
                                text = "${stat.calls} calls",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "busy ${formatDuration(stat.busyMs)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "peak x${stat.maxConcurrent}",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (stat.isCongested) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }
                    }
                    stat.contentionReason?.let { reason ->
                        Text(
                            text = "contended by $reason",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
    }
}


@Composable
private fun SummarySlot(content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) { content() }
}

private val CALLS_COLUMN = 52.dp
private val TIME_COLUMN = 62.dp

@Composable
private fun MethodTableHeader(sortBy: MethodSort, onSort: (MethodSort) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(start = 8.dp, end = 8.dp, top = 6.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Caption("method", uppercase = true, modifier = Modifier.weight(1f))
        SortableColumn("calls", MethodSort.CALLS, sortBy, CALLS_COLUMN, onSort)
        SortableColumn("total", MethodSort.TOTAL, sortBy, TIME_COLUMN, onSort)
        SortableColumn("avg", MethodSort.AVG, sortBy, TIME_COLUMN, onSort)
        SortableColumn("max", MethodSort.MAX, sortBy, TIME_COLUMN, onSort)
    }
}

@Composable
private fun SortableColumn(
    label: String,
    sort: MethodSort,
    active: MethodSort,
    width: Dp,
    onSort: (MethodSort) -> Unit
) {
    val selected = sort == active
    Box(
        modifier = Modifier.width(width).clickable { onSort(sort) },
        contentAlignment = Alignment.CenterEnd
    ) {
        Text(
            text = if (selected) "$label ▾" else label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            maxLines = 1
        )
    }
}

@Composable
private fun MethodStatsRow(stat: MethodStats, maxTotal: Long) {
    var expanded by remember(stat.methodIdentifier) { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val colors = MaterialTheme.colorScheme

    val hasFailures = stat.failures > 0
    val accent = when {
        hasFailures -> colors.error
        stat.isCongested -> colors.tertiary
        stat.kind == SpanKind.GUARD -> colors.secondary
        else -> Color.Transparent
    }
    val fraction = if (maxTotal > 0) (stat.totalMs.toFloat() / maxTotal).coerceIn(0f, 1f) else 0f

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .hoverable(interaction)
            .clickable { expanded = !expanded }
            .background(if (hovered) colors.surfaceVariant.copy(alpha = 0.4f) else Color.Transparent)
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            Row(modifier = Modifier.matchParentSize()) {
                if (fraction > 0f) {
                    Box(
                        modifier = Modifier
                            .weight(fraction)
                            .fillMaxHeight()
                            .background(
                                if (hasFailures) {
                                    colors.error.copy(alpha = 0.14f)
                                } else {
                                    colors.primary.copy(alpha = 0.13f)
                                }
                            )
                    )
                }
                if (fraction < 1f) Spacer(modifier = Modifier.weight(1f - fraction))
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 3.dp, bottom = 3.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .height(13.dp)
                        .background(accent)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = stat.methodIdentifier,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = colors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                NumberCell(stat.calls.toString(), CALLS_COLUMN)
                NumberCell(formatDuration(stat.totalMs), TIME_COLUMN, emphasis = true)
                NumberCell(formatDuration(stat.avgMs), TIME_COLUMN)
                NumberCell(formatDuration(stat.maxMs), TIME_COLUMN)
            }
        }

        if (expanded) {
            MethodStatsDetail(stat)
        }
    }
}

@Composable
private fun NumberCell(value: String, width: Dp, emphasis: Boolean = false) {
    Text(
        text = value,
        style = MaterialTheme.typography.labelSmall,
        fontFamily = FontFamily.Monospace,
        color = if (emphasis) {
            MaterialTheme.colorScheme.onSurface
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        maxLines = 1,
        textAlign = TextAlign.End,
        modifier = Modifier.width(width)
    )
}

@Composable
private fun MethodStatsDetail(stat: MethodStats) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        stat.location?.let { location ->
            val url = stat.githubSourceUrl
            if (url != null) {
                Text(
                    text = location,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    textDecoration = TextDecoration.Underline,
                    maxLines = 1,
                    modifier = Modifier.clickable { openInBrowser(url) }
                )
            } else {
                Caption(location, singleLine = true)
            }
        }
        if (stat.kind == SpanKind.GUARD) {
            Caption("navigation guard", singleLine = true)
        }
        if (stat.threads.isNotEmpty()) {
            Caption("entered on ${stat.threads.joinToString(", ")}", singleLine = true)
        }
        if (stat.dispatchers.isNotEmpty()) {
            Caption("via ${stat.dispatchers.joinToString(", ")}", singleLine = true)
        }
        if (stat.isCongested) {
            Text(
                text = "peak x${stat.maxConcurrent} concurrent",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error
            )
        }
        if (stat.inFlight > 0) {
            Text(
                text = "${stat.inFlight} still running",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary
            )
        }
        if (stat.failures > 0) {
            Text(
                text = "${stat.failures} failed",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error
            )
        }
        stat.failureSummary?.let { summary ->
            SelectionContainer {
                Text(
                    text = "Failed with $summary",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun StatLabel(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

private fun displayedStatsText(stats: List<MethodStats>): String =
    stats.joinToString(separator = "\n") {
        it.methodIdentifier + "  calls=" + it.calls + "  total=" + it.totalMs +
            "ms  avg=" + it.avgMs + "ms  max=" + it.maxMs + "ms"
    }
