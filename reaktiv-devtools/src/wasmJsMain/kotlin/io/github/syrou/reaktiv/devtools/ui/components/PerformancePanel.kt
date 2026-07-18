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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.syrou.reaktiv.devtools.protocol.MethodStats
import io.github.syrou.reaktiv.devtools.protocol.ThreadStats
import io.github.syrou.reaktiv.devtools.protocol.aggregateLogicStats
import io.github.syrou.reaktiv.devtools.protocol.aggregateThreadStats
import io.github.syrou.reaktiv.devtools.ui.LogicMethodEvent

@Composable
fun PerformancePanel(
    logicMethodEvents: List<LogicMethodEvent>
) {
    val started = remember(logicMethodEvents.size) {
        logicMethodEvents.filterIsInstance<LogicMethodEvent.Started>().map { it.event }
    }
    val completed = remember(logicMethodEvents.size) {
        logicMethodEvents.filterIsInstance<LogicMethodEvent.Completed>().map { it.event }
    }
    val failed = remember(logicMethodEvents.size) {
        logicMethodEvents.filterIsInstance<LogicMethodEvent.Failed>().map { it.event }
    }
    val stats = remember(logicMethodEvents.size) { aggregateLogicStats(started, completed, failed) }
    val threadStats = remember(logicMethodEvents.size) { aggregateThreadStats(started, completed, failed) }
    val maxTotal = stats.maxOfOrNull { it.totalMs } ?: 0L

    val mainThreadMethods = stats.filter { it.runsOnMainThread }
    val congestedMethods = stats.filter { it.isCongested }
    val congestedThreads = threadStats.filter { it.isCongested }

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
            Text(
                text = "${stats.size} methods, ${stats.sumOf { it.calls }} calls",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (mainThreadMethods.isNotEmpty() || congestedMethods.isNotEmpty() || congestedThreads.isNotEmpty()) {
            WarningBanner(
                mainThreadMethods = mainThreadMethods,
                congestedMethods = congestedMethods,
                congestedThreads = congestedThreads
            )
            Spacer(modifier = Modifier.height(12.dp))
        }

        if (threadStats.isNotEmpty()) {
            ThreadSummary(threadStats)
            Spacer(modifier = Modifier.height(12.dp))
        }

        if (stats.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No logic trace events captured yet",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            Box(modifier = Modifier.fillMaxSize()) {
                val listState = rememberLazyListState()
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(stats, key = { it.methodIdentifier }) { stat ->
                        MethodStatsCard(stat = stat, maxTotal = maxTotal)
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
private fun WarningBanner(
    mainThreadMethods: List<MethodStats>,
    congestedMethods: List<MethodStats>,
    congestedThreads: List<ThreadStats>
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
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = "Performance warnings",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.error
            )
            if (mainThreadMethods.isNotEmpty()) {
                Text(
                    text = "Main thread: ${mainThreadMethods.size} logic method(s) run on the main thread: " +
                        mainThreadMethods.joinToString(", ") { it.methodIdentifier },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
            if (congestedMethods.isNotEmpty()) {
                Text(
                    text = "Congestion: " + congestedMethods.joinToString(", ") {
                        "${it.methodIdentifier} peaks at ${it.maxConcurrent} concurrent calls"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
            if (congestedThreads.isNotEmpty()) {
                Text(
                    text = "Contention: " + congestedThreads.joinToString(", ") {
                        "${it.thread} runs up to ${it.maxConcurrent} overlapping calls"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
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
            }
        }
    }
}

@Composable
private fun MethodStatsCard(stat: MethodStats, maxTotal: Long) {
    val isGuard = stat.logicClass == "NavigationGuards"
    val hasFailures = stat.failures > 0
    val hasWarning = stat.runsOnMainThread || stat.isCongested

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when {
                hasFailures || hasWarning -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = stat.methodIdentifier,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1
                    )
                    if (isGuard) {
                        Text(
                            text = "guard",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    }
                }
                Text(
                    text = "total ${formatDuration(stat.totalMs)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            if (maxTotal > 0) {
                Row(modifier = Modifier.fillMaxWidth().height(4.dp)) {
                    val fraction = (stat.totalMs.toFloat() / maxTotal).coerceIn(0f, 1f)
                    if (fraction > 0f) {
                        Box(
                            modifier = Modifier
                                .weight(fraction)
                                .fillMaxHeight()
                                .background(
                                    color = if (hasFailures || hasWarning) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        MaterialTheme.colorScheme.primary
                                    },
                                    shape = RoundedCornerShape(2.dp)
                                )
                        )
                    }
                    if (fraction < 1f) {
                        Spacer(modifier = Modifier.weight(1f - fraction))
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                StatLabel("calls", stat.calls.toString())
                StatLabel("avg", formatDuration(stat.avgMs))
                StatLabel("max", formatDuration(stat.maxMs))
                if (stat.failures > 0) {
                    Text(
                        text = "${stat.failures} failed",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                if (stat.inFlight > 0) {
                    Text(
                        text = "${stat.inFlight} running",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
            }

            if (stat.threads.isNotEmpty() || stat.dispatchers.isNotEmpty() || hasWarning) {
                Spacer(modifier = Modifier.height(2.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (stat.threads.isNotEmpty()) {
                        Text(
                            text = "on ${stat.threads.joinToString(", ")}",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (stat.runsOnMainThread) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            maxLines = 1
                        )
                    }
                    if (stat.dispatchers.isNotEmpty()) {
                        Text(
                            text = "via ${stat.dispatchers.joinToString(", ")}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                    if (stat.isCongested) {
                        Text(
                            text = "peak x${stat.maxConcurrent}",
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

private fun formatDuration(ms: Long): String = when {
    ms >= 60_000 -> "${ms / 60_000}m ${(ms % 60_000) / 1000}s"
    ms >= 1000 -> {
        val tenths = (ms % 1000) / 100
        "${ms / 1000}.${tenths}s"
    }
    else -> "${ms}ms"
}
