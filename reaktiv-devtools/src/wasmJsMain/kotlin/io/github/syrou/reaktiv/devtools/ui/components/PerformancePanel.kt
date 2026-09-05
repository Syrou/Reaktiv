package io.github.syrou.reaktiv.devtools.ui.components

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
import io.github.syrou.reaktiv.devtools.protocol.DISPATCH_TRACE_CLASS
import io.github.syrou.reaktiv.devtools.protocol.DispatchStats
import io.github.syrou.reaktiv.devtools.protocol.MethodStats
import io.github.syrou.reaktiv.devtools.protocol.SYNTHETIC_TRACE_CLASSES
import io.github.syrou.reaktiv.devtools.protocol.asClipboardText
import io.github.syrou.reaktiv.devtools.protocol.Finding
import io.github.syrou.reaktiv.devtools.protocol.GUARD_TRACE_CLASS
import io.github.syrou.reaktiv.devtools.protocol.ModuleSizeStats
import io.github.syrou.reaktiv.devtools.protocol.STALL_TRACE_CLASS
import io.github.syrou.reaktiv.devtools.protocol.StallGroup
import io.github.syrou.reaktiv.devtools.protocol.StateSizeTracker
import io.github.syrou.reaktiv.devtools.protocol.ThreadStats
import io.github.syrou.reaktiv.devtools.protocol.aggregateDispatchStats
import io.github.syrou.reaktiv.devtools.protocol.aggregateLogicStats
import io.github.syrou.reaktiv.devtools.protocol.aggregateStalls
import io.github.syrou.reaktiv.devtools.protocol.aggregateThreadStats
import io.github.syrou.reaktiv.devtools.ui.LogicMethodEvent
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
    dataRevision: Long,
    logicMethodEvents: List<LogicMethodEvent>,
    findings: List<Finding>,
    searchQuery: String = "",
    searchField: @Composable () -> Unit = {},
    actionStateHistory: List<CapturedAction> = emptyList(),
    initialStateJson: String = "{}",
) {
    val started = remember(dataRevision) {
        logicMethodEvents.filterIsInstance<LogicMethodEvent.Started>().map { it.event }
    }
    val completed = remember(dataRevision) {
        logicMethodEvents.filterIsInstance<LogicMethodEvent.Completed>().map { it.event }
    }
    val failed = remember(dataRevision) {
        logicMethodEvents.filterIsInstance<LogicMethodEvent.Failed>().map { it.event }
    }
    val stats = remember(dataRevision) { aggregateLogicStats(started, completed, failed) }
    val threadStats = remember(dataRevision) { aggregateThreadStats(started, completed, failed) }
    val dispatchStats = remember(dataRevision) { aggregateDispatchStats(started, completed, failed) }
    val maxTotal = stats.maxOfOrNull { it.totalMs } ?: 0L

    val stallStats = stats.firstOrNull { it.logicClass == STALL_TRACE_CLASS }
    val stallGroups = remember(dataRevision) { aggregateStalls(started, completed) }
    val congestedMethods = stats.filter {
        it.isCongested && it.logicClass != DISPATCH_TRACE_CLASS && it.logicClass != STALL_TRACE_CLASS
    }
    val congestedThreads = threadStats.filter { it.isCongested }
    val dispatchCongested = dispatchStats?.isCongested == true

    val sizeTracker = remember(initialStateJson) {
        StateSizeTracker().also { it.feedInitial(initialStateJson) }
    }
    val sizeStats = remember(dataRevision, initialStateJson) {
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
    val suspiciousModules = sizeStats.filter { it.isSuspicious }

    var sortBy by remember { mutableStateOf(MethodSort.TOTAL) }
    var warningsExpanded by remember { mutableStateOf(false) }
    var showPipeline by remember { mutableStateOf(false) }
    val pipelineCount = stats.count { it.logicClass in SYNTHETIC_TRACE_CLASSES }
    val shownStats = stats
        .filter { searchQuery.isBlank() || it.methodIdentifier.contains(searchQuery, ignoreCase = true) }
        .filter { showPipeline || it.logicClass !in SYNTHETIC_TRACE_CLASSES }

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
                        Text("Warnings ${findings.size}", style = MaterialTheme.typography.labelSmall)
                    }
                )
                CopyControl(
                    actions = listOf(
                        CopyAction("all warnings") {
                            findings.joinToString(separator = "\n") { it.asClipboardText() }
                        }
                    ),
                    enabled = findings.isNotEmpty()
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
        val hasAnyWarning = congestedMethods.isNotEmpty() ||
            congestedThreads.isNotEmpty() || dispatchCongested || suspiciousModules.isNotEmpty() ||
            stallStats != null

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
                        SummarySlot { WarningBanner(
                            congestedMethods = congestedMethods,
                            congestedThreads = congestedThreads,
                            dispatchStats = dispatchStats?.takeIf { it.isCongested },
                            suspiciousModules = suspiciousModules,
                            stallStats = stallStats,
                            stallGroups = stallGroups,
                            methodStats = stats
                        ) }
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
private fun WarningBanner(
    congestedMethods: List<MethodStats>,
    congestedThreads: List<ThreadStats>,
    dispatchStats: DispatchStats? = null,
    suspiciousModules: List<ModuleSizeStats> = emptyList(),
    stallStats: MethodStats? = null,
    stallGroups: List<StallGroup> = emptyList(),
    methodStats: List<MethodStats> = emptyList()
) {
    val byId = remember(methodStats) { methodStats.associateBy { it.methodIdentifier } }
    fun withLocation(methodId: String): String =
        byId[methodId]?.location?.let { "$methodId ($it)" } ?: methodId
    fun statsFor(ids: Collection<String>): List<MethodStats> = ids.mapNotNull { byId[it] }

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
            if (stallStats != null) {
                val distinctCauses = stallGroups.count { it.stack != null }
                val stackBlocks = stallGroups.mapNotNull { group ->
                    group.stack?.let {
                        StackBlock(
                            label = "${group.count} freeze(s), worst ${formatDuration(group.worstMs)}",
                            stack = it
                        )
                    }
                }
                WarningRow(
                    text = "UI frozen ${stallStats.finished} time(s), worst ${formatDuration(stallStats.maxMs)}" +
                        if (distinctCauses > 1) " from $distinctCauses different stacks" else "",
                    stackBlocks = stackBlocks,
                    note = if (stackBlocks.isEmpty()) {
                        "The main thread stack could not be captured on this platform, so the exact blocker " +
                            "is not shown here. The freeze is real: look for blocking work on Main in a " +
                            "Composable, a reducer, or synchronous IO/serialization."
                    } else null,
                    meaning = "The main thread stopped making progress long enough to be noticed, so " +
                        "something ran a blocking or long operation on it instead of yielding. Each stack below " +
                        "is a distinct freeze captured at the moment it was detected, so its topmost frames are " +
                        "what was actually blocking the UI that time.",
                    impact = "While the main thread is blocked nothing renders and no input is handled, so " +
                        "the app looks frozen and Android can raise an ANR.",
                    fix = "Move the blocking work shown in each stack off Main: do it inside a ModuleLogic " +
                        "method wrapped in withContext(Dispatchers.Default), keep reducers pure, and dispatch a " +
                        "result action when it finishes so the UI only ever renders state, never computes it."
                )
            }
            if (congestedMethods.isNotEmpty()) {
                WarningRow(
                    text = "Congestion: " + congestedMethods.joinToString("; ") {
                        "${it.methodIdentifier} - ${it.congestionReason ?: "${it.maxConcurrent} concurrent"}"
                    },
                    culprits = congestedMethods,
                    culpritLabel = "Open",
                    meaning = "A new call to this logic method started before the previous one finished, so " +
                        "several ran at once.",
                    impact = "Overlapping calls duplicate work and can race on shared resources, and when " +
                        "they interleave on one thread they starve each other, wasting time and risking " +
                        "inconsistent state.",
                    fix = "Conflate the trigger: debounce inside the logic method, collapse rapid updates " +
                        "into one action, or hold a Mutex in the ModuleLogic so calls serialize. Move IO onto " +
                        "Dispatchers.Default so genuinely parallel calls stop interleaving on one thread."
                )
            }
            if (congestedThreads.isNotEmpty()) {
                WarningRow(
                    text = "Contention: " + congestedThreads.joinToString("; ") { thread ->
                        val who = thread.contenders.joinToString(", ") { withLocation(it) }
                        "${thread.thread} peaks at ${thread.maxConcurrent}" +
                            (if (who.isNotEmpty()) " ($who)" else "")
                    },
                    culprits = congestedThreads.flatMap { statsFor(it.contenders) }.distinct(),
                    culpritLabel = "Open",
                    meaning = "Several different logic methods were running on the same thread at the same time.",
                    impact = "A single thread makes progress on only one at a time, so they take turns and " +
                        "each finishes later than it would alone, adding latency across unrelated features.",
                    fix = "Give the store a multi-threaded pool via " +
                        "createStore { coroutineContext(Dispatchers.Default) }, or move the competing " +
                        "ModuleLogic work into their own withContext(Dispatchers.IO) blocks so one thread is " +
                        "not oversubscribed."
                )
            }
            if (dispatchStats != null) {
                WarningRow(
                    text = "Dispatch queue: wait peaked at ${dispatchStats.maxQueueWaitMs}ms " +
                        "with depth ${dispatchStats.maxQueueDepth}",
                    meaning = "Actions piled up in the store's single ordered dispatch channel faster than " +
                        "reducers drained them.",
                    impact = "Every dispatched action waits behind the backlog, so state updates and the UI " +
                        "lag behind input even when the reducers themselves are simple.",
                    fix = "Keep reducers pure and O(1), move side effects out of middleware into ModuleLogic, " +
                        "batch high-frequency dispatches into fewer actions, and avoid dispatchAndAwait in hot " +
                        "loops since it makes the producer wait for the reducer."
                )
            }
            if (suspiciousModules.isNotEmpty()) {
                WarningRow(
                    text = "State growth: " + suspiciousModules.joinToString(", ") {
                        "${it.shortName} +${it.growthPercent}% this session, now ${formatBytes(it.currentBytes)}"
                    },
                    meaning = "This ModuleState has grown steadily all session, the signature of an " +
                        "append-only collection that is never trimmed.",
                    impact = "Reaktiv persists, replicates and captures full state, so unbounded growth " +
                        "inflates every snapshot, delta and crash file, slows serialization and eventually " +
                        "leaks memory.",
                    fix = "Cap or prune the collection in the reducer, or keep large data in a " +
                        "ModuleLogic-owned repository and store only ids in state."
                )
            }
        }
    }
}

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
        stat.logicClass == GUARD_TRACE_CLASS -> colors.secondary
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
        if (stat.logicClass == GUARD_TRACE_CLASS) {
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

private enum class MethodSort { TOTAL, MAX, AVG, CALLS }

private fun displayedStatsText(stats: List<MethodStats>): String =
    stats.joinToString(separator = "\n") {
        it.methodIdentifier + "  calls=" + it.calls + "  total=" + it.totalMs +
            "ms  avg=" + it.avgMs + "ms  max=" + it.maxMs + "ms"
    }
