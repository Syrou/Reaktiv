package io.github.syrou.reaktiv.devtools.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.syrou.reaktiv.devtools.ui.DeviceLogRow

internal val LOG_LEVELS: List<String> = listOf("ERROR", "WARN", "INFO", "DEBUG")

@Composable
internal fun LogsPanel(
    logs: List<DeviceLogRow>,
    hiddenLevels: Set<String>,
    searchQuery: String,
    searchField: @Composable () -> Unit,
    onToggleLevel: (String) -> Unit,
    onClearSearch: () -> Unit
) {
    val visible = remember(logs, hiddenLevels, searchQuery) {
        logs.filter { log ->
            log.level !in hiddenLevels &&
                (searchQuery.isBlank() ||
                    log.message.contains(searchQuery, ignoreCase = true) ||
                    log.category.contains(searchQuery, ignoreCase = true) ||
                    log.level.contains(searchQuery, ignoreCase = true))
        }
    }
    val levelsPresent = remember(logs) { logs.map { it.level }.distinct() }
    val levels = (LOG_LEVELS + levelsPresent).distinct()

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
                Text(text = "Logs", style = MaterialTheme.typography.titleSmall)
                Text(
                    text = "${visible.size} of ${logs.size} lines",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.width(6.dp))
            searchField()
            Spacer(modifier = Modifier.weight(1f))
            CopyControl(
                actions = listOf(
                    CopyAction("visible lines") { visible.joinToString("\n") { it.asClipboardLine() } },
                    CopyAction("all lines") { logs.joinToString("\n") { it.asClipboardLine() } }
                ),
                enabled = logs.isNotEmpty()
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            levels.forEach { level ->
                FilterChip(
                    selected = level !in hiddenLevels,
                    onClick = { onToggleLevel(level) },
                    label = { Text(level, style = MaterialTheme.typography.labelSmall, maxLines = 1) }
                )
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        if (visible.isEmpty()) {
            if (logs.isEmpty()) {
                EmptyState(
                    title = "No log lines yet",
                    detail = "Anything the app hands to ReaktivDebug, including a logging facade " +
                        "forwarding through ReaktivDebug.log, arrives here while the device publishes."
                )
            } else {
                FilteredEmptyState(
                    query = searchQuery,
                    hiddenCount = logs.size,
                    onClearFilters = {
                        onClearSearch()
                        hiddenLevels.forEach(onToggleLevel)
                    }
                )
            }
            return@Column
        }
        val listState = rememberLazyListState()
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            items(visible, key = { "${it.timestampMs}:${it.category}:${it.message.hashCode()}" }) { log ->
                LogLine(log)
            }
        }
    }
}

private fun DeviceLogRow.asClipboardLine(): String =
    "${formatClockTime(timestampMs)} ${level.padEnd(5)} [$category] $message"

@Composable
private fun LogLine(log: DeviceLogRow) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val levelColor = when (log.level) {
        "ERROR" -> colors.error
        "WARN" -> colors.tertiary
        else -> colors.onSurfaceVariant
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .hoverable(interaction)
            .background(if (hovered) colors.surfaceVariant.copy(alpha = 0.5f) else colors.surface)
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = formatClockTime(log.timestampMs),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = colors.onSurfaceVariant,
            modifier = Modifier.width(88.dp)
        )
        Text(
            text = log.level,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = levelColor,
            modifier = Modifier.width(44.dp)
        )
        Text(
            text = log.category,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = colors.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(110.dp)
        )
        Text(
            text = log.message,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = if (log.level == "ERROR") colors.error else colors.onSurface,
            modifier = Modifier.weight(1f)
        )
        CopyControl(
            actions = listOf(
                CopyAction("line") { log.asClipboardLine() },
                CopyAction("message") { log.message }
            ),
            dense = true,
            tint = colors.onSurfaceVariant.copy(alpha = if (hovered) 0.9f else 0.3f)
        )
    }
}
