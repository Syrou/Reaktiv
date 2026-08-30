package io.github.syrou.reaktiv.devtools.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.syrou.reaktiv.devtools.protocol.NavigationAttempt
import io.github.syrou.reaktiv.devtools.protocol.NavigationEntrySnapshot
import io.github.syrou.reaktiv.devtools.protocol.NavigationSnapshot
import io.github.syrou.reaktiv.devtools.protocol.buildNavigationLog
import io.github.syrou.reaktiv.devtools.protocol.parseNavigationState
import io.github.syrou.reaktiv.devtools.ui.LogicMethodEvent
import io.github.syrou.reaktiv.introspection.protocol.CapturedAction
import io.github.syrou.reaktiv.introspection.protocol.StateReconstructor

/**
 * Where navigation is, and how it got there.
 *
 * The back stack is read out of the reconstructed state at the selected action, so stepping
 * through the session steps through navigation. The log underneath pairs every navigate call and
 * guard evaluation with its verdict, which is the part that explains a redirect nobody asked for.
 */
@Composable
internal fun NavigationPanel(
    dataRevision: Long,
    actionStateHistory: List<CapturedAction>,
    selectedActionIndex: Int?,
    initialStateJson: String,
    logicMethodEvents: List<LogicMethodEvent>
) {
    val snapshot = remember(dataRevision, selectedActionIndex, initialStateJson) {
        val stateJson = when {
            actionStateHistory.isEmpty() -> initialStateJson
            selectedActionIndex == null -> initialStateJson
            else -> StateReconstructor.reconstructAtIndex(
                initialStateJson, actionStateHistory, selectedActionIndex
            )
        }
        parseNavigationState(stateJson)
    }

    val log = remember(dataRevision) {
        buildNavigationLog(
            starts = logicMethodEvents.filterIsInstance<LogicMethodEvent.Started>().map { it.event },
            completions = logicMethodEvents.filterIsInstance<LogicMethodEvent.Completed>().map { it.event }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (snapshot == null) {
            EmptyState(
                title = "This app has no navigation module",
                detail = "The back stack and guard log appear for any store with " +
                    "reaktiv-navigation registered.",
                modifier = Modifier.fillMaxWidth().weight(1f)
            )
        } else {
            PositionCard(snapshot)
            BackStackList(snapshot, modifier = Modifier.weight(1f))
        }

        NavigationLogList(log, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun PositionCard(snapshot: NavigationSnapshot) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Text(
                text = snapshot.currentPath ?: "unresolved",
                style = MaterialTheme.typography.titleSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.primary
            )
            if (snapshot.graphChain.isNotEmpty()) {
                Text(
                    text = "In graph: ${snapshot.graphChain.joinToString(" / ")}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(top = 6.dp)
            ) {
                if (snapshot.isBootstrapping) StateChip("bootstrapping", MaterialTheme.colorScheme.tertiary)
                if (snapshot.isEvaluating) StateChip("evaluating", MaterialTheme.colorScheme.tertiary)
                if (snapshot.isCurrentModal) StateChip("modal", MaterialTheme.colorScheme.secondary)
                if (snapshot.modalContextPaths.isNotEmpty()) {
                    StateChip(
                        "${snapshot.modalContextPaths.size} modal context",
                        MaterialTheme.colorScheme.secondary
                    )
                }
            }
        }
    }
}

@Composable
private fun StateChip(label: String, color: Color) {
    Surface(color = color.copy(alpha = 0.18f), shape = RoundedCornerShape(3.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun BackStackList(snapshot: NavigationSnapshot, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    Column(modifier = modifier) {
        SectionHeading("Back stack", "${snapshot.backStack.size}")
        Box(modifier = Modifier.fillMaxSize()) {
            val depth = snapshot.backStack.size - 1
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                itemsIndexed(snapshot.backStack.asReversed()) { indexFromTop, entry ->
                    BackStackRow(
                        entry = entry,
                        depth = depth - indexFromTop,
                        isCurrent = indexFromTop == 0
                    )
                }
            }
        }
    }
}

@Composable
private fun BackStackRow(entry: NavigationEntrySnapshot, depth: Int, isCurrent: Boolean) {
    val accent = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(color = accent, modifier = Modifier.width(3.dp)) { Box(Modifier.padding(vertical = 8.dp)) }
        Column(modifier = Modifier.padding(start = 8.dp)) {
            Text(
                text = entry.route,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                color = if (isCurrent) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "$depth  ${entry.path}" +
                    if (entry.params.isNotEmpty()) "  ${entry.params}" else "",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun NavigationLogList(log: List<NavigationAttempt>, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    Column(modifier = modifier) {
        SectionHeading("Navigation log", "${log.size}")
        if (log.isEmpty()) {
            EmptyState(
                title = "No navigation attempts captured",
                detail = "Navigate calls and guard verdicts are recorded by the tracing compiler " +
                    "plugin. Set reaktivTracing.enabled for this build and reconnect."
            )
            return@Column
        }
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            items(log.asReversed()) { attempt -> NavigationLogRow(attempt) }
        }
    }
}

@Composable
private fun NavigationLogRow(attempt: NavigationAttempt) {
    val outcomeColor = when {
        attempt.outcome == null -> MaterialTheme.colorScheme.onSurfaceVariant
        attempt.diverted -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.primary
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.Top
    ) {
        Surface(color = outcomeColor, modifier = Modifier.width(3.dp)) {
            Box(Modifier.padding(vertical = 8.dp))
        }
        Column(modifier = Modifier.padding(start = 8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = attempt.outcome ?: "running",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    color = outcomeColor
                )
                if (attempt.isGuard) StateChip("guard", MaterialTheme.colorScheme.tertiary)
            }
            Text(
                text = "${attempt.name}  ->  ${attempt.target}",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            attempt.durationMs?.let {
                Text(
                    text = "${it}ms",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
