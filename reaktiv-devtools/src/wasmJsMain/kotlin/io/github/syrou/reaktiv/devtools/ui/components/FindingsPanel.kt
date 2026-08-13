package io.github.syrou.reaktiv.devtools.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import io.github.syrou.reaktiv.core.tracing.StateRead
import io.github.syrou.reaktiv.devtools.protocol.Finding
import io.github.syrou.reaktiv.devtools.protocol.FindingSeverity
import io.github.syrou.reaktiv.devtools.protocol.StateSizeTracker
import io.github.syrou.reaktiv.devtools.protocol.aggregateChurn
import io.github.syrou.reaktiv.devtools.protocol.asClipboardText
import io.github.syrou.reaktiv.devtools.protocol.computeFindings
import io.github.syrou.reaktiv.devtools.ui.LogicMethodEvent
import io.github.syrou.reaktiv.introspection.protocol.CapturedAction

@Composable
internal fun FindingsPanel(
    dataRevision: Long,
    logicMethodEvents: List<LogicMethodEvent>,
    actionStateHistory: List<CapturedAction>,
    initialStateJson: String,
    stateReads: List<StateRead>,
    onSeekTimestamp: (Long) -> Unit = {}
) {
    val started = remember(dataRevision) {
        logicMethodEvents.filterIsInstance<LogicMethodEvent.Started>().map { it.event }
    }
    val completed = remember(dataRevision) {
        logicMethodEvents.filterIsInstance<LogicMethodEvent.Completed>().map { it.event }
    }
    val sizes = remember(dataRevision, initialStateJson) {
        StateSizeTracker().also { tracker ->
            tracker.feedInitial(initialStateJson)
            actionStateHistory.forEach { tracker.feed(it) }
        }.snapshot()
    }
    val churn = remember(dataRevision) {
        aggregateChurn(actionStateHistory, stateReads)
    }
    val findings = remember(dataRevision) {
        computeFindings(started, completed, sizes, churn)
    }

    var severityFilter by remember { mutableStateOf<FindingSeverity?>(null) }
    val criticalCount = findings.count { it.severity == FindingSeverity.CRITICAL }
    val warningCount = findings.size - criticalCount
    val visible = findings.filter { severityFilter == null || it.severity == severityFilter }

    Column(modifier = Modifier.fillMaxSize().padding(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Findings",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = "click a finding to jump",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            FilterChip(
                selected = severityFilter == null,
                onClick = { severityFilter = null },
                label = { Text("All ${findings.size}", style = MaterialTheme.typography.labelSmall) }
            )
            FilterChip(
                selected = severityFilter == FindingSeverity.CRITICAL,
                onClick = { severityFilter = FindingSeverity.CRITICAL },
                label = { Text("Critical $criticalCount", style = MaterialTheme.typography.labelSmall) }
            )
            FilterChip(
                selected = severityFilter == FindingSeverity.WARNING,
                onClick = { severityFilter = FindingSeverity.WARNING },
                label = { Text("Warnings $warningCount", style = MaterialTheme.typography.labelSmall) }
            )
            IconButton(
                onClick = {
                    copyTextToClipboard(visible.joinToString(separator = "\n") { it.asClipboardText() })
                },
                enabled = visible.isNotEmpty(),
                modifier = Modifier.size(26.dp)
            ) {
                Icon(
                    Icons.Default.ContentCopy,
                    contentDescription = "Copy all findings",
                    modifier = Modifier.size(15.dp)
                )
            }
        }

        if (visible.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = if (findings.isEmpty()) "No findings in this session" else "Nothing at this severity",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(visible) { finding ->
                    FindingCard(finding, onSeekTimestamp)
                }
            }
        }
    }
}

@Composable
private fun FindingCard(finding: Finding, onSeekTimestamp: (Long) -> Unit) {
    val critical = finding.severity == FindingSeverity.CRITICAL
    val containerColor = if (critical) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val timestamp = finding.timestampMs
    Card(
        colors = CardDefaults.cardColors(containerColor = containerColor),
        modifier = if (timestamp != null) {
            Modifier.clickable { onSeekTimestamp(timestamp) }
        } else {
            Modifier
        }
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = if (critical) Icons.Default.Error else Icons.Default.Warning,
                contentDescription = finding.severity.name,
                tint = if (critical) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.tertiary
                }
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = finding.category,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = finding.title,
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    text = finding.detail,
                    style = MaterialTheme.typography.bodySmall
                )
                val location = finding.sourceFile?.let { file ->
                    finding.lineNumber?.let { line -> "$file:$line" } ?: file
                }
                if (location != null) {
                    Text(
                        text = location,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            IconButton(
                onClick = { copyTextToClipboard(finding.asClipboardText()) },
                modifier = Modifier.size(24.dp)
            ) {
                Icon(
                    Icons.Default.ContentCopy,
                    contentDescription = "Copy finding",
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }
}

