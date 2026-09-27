package io.github.syrou.reaktiv.devtools.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.DataObject
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.syrou.reaktiv.devtools.ui.DevToolsDestination

internal val RAIL_WIDTH = 56.dp

private fun DevToolsDestination.icon(): ImageVector = when (this) {
    DevToolsDestination.STREAM -> Icons.AutoMirrored.Filled.List
    DevToolsDestination.STATE -> Icons.Default.DataObject
    DevToolsDestination.NAVIGATION -> Icons.Default.AccountTree
    DevToolsDestination.PERFORMANCE -> Icons.Default.Speed
    DevToolsDestination.NETWORK -> Icons.Default.Language
    DevToolsDestination.FINDINGS -> Icons.Default.Warning
    DevToolsDestination.LOGS -> Icons.AutoMirrored.Filled.Notes
    DevToolsDestination.DEVICES -> Icons.Default.Devices
    DevToolsDestination.SESSIONS -> Icons.Default.History
}

@Composable
internal fun DestinationRail(
    current: DevToolsDestination,
    findingsCount: Int,
    hasCriticalFinding: Boolean,
    deviceCount: Int,
    onSelect: (DevToolsDestination) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width(RAIL_WIDTH)
            .background(MaterialTheme.colorScheme.surface)
    ) {
        DevToolsDestination.entries.forEach { destination ->
            if (destination == DevToolsDestination.DEVICES) {
                Spacer(modifier = Modifier.weight(1f))
            }
            val badge = when (destination) {
                DevToolsDestination.FINDINGS -> findingsCount.takeIf { it > 0 }?.toString()
                DevToolsDestination.DEVICES -> deviceCount.takeIf { it > 0 }?.toString()
                else -> null
            }
            RailItem(
                destination = destination,
                selected = destination == current,
                badge = badge,
                badgeCritical = destination == DevToolsDestination.FINDINGS && hasCriticalFinding,
                onClick = { onSelect(destination) }
            )
        }
    }
}

@Composable
private fun RailItem(
    destination: DevToolsDestination,
    selected: Boolean,
    badge: String?,
    badgeCritical: Boolean,
    onClick: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val tint = if (selected) colors.primary else colors.onSurfaceVariant
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clickable(onClick = onClick)
    ) {
        if (selected) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .width(2.dp)
                    .height(28.dp)
                    .background(colors.primary)
            )
        }
        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = destination.icon(),
                contentDescription = destination.label,
                tint = tint,
                modifier = Modifier.size(20.dp)
            )
            Text(
                text = destination.label,
                style = MaterialTheme.typography.labelSmall,
                color = tint,
                textAlign = TextAlign.Center,
                maxLines = 1
            )
        }
        if (badge != null) {
            Text(
                text = badge,
                style = MaterialTheme.typography.labelSmall,
                color = if (badgeCritical) colors.onErrorContainer else colors.onSurface,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 4.dp, end = 6.dp)
                    .background(
                        color = if (badgeCritical) colors.errorContainer else colors.surfaceVariant,
                        shape = RoundedCornerShape(8.dp)
                    )
                    .padding(horizontal = 5.dp, vertical = 1.dp)
            )
        }
    }
}
