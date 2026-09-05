package io.github.syrou.reaktiv.devtools.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

private val SHORTCUTS = listOf(
    "Ctrl+K" to "Command palette",
    "/" to "Search this list",
    "t" to "Toggle time travel",
    "Space" to "Play or pause playback",
    "j / Left" to "Step to previous action",
    "k / Right" to "Step to next action",
    "1 - 9" to "Stream, State, Nav, Perf, Net, Findings, Logs, Devices, Sessions",
    "m" to "Drop a marker on the publisher",
    "g" to "Import a ghost session",
    "e" to "Export the current session",
    "d" to "Devices",
    "?" to "This help",
    "Esc" to "Close overlays",
    "" to "",
    "Timeline (focused)" to "",
    "Wheel" to "Zoom at cursor",
    "Drag" to "Pan",
    "Double-click span" to "Zoom to span",
    "W / S" to "Zoom in / out",
    "A / D" to "Pan left / right",
    "F" to "Fit whole session"
)

@Composable
internal fun HelpOverlay(onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Keyboard shortcuts",
                        style = MaterialTheme.typography.titleMedium
                    )
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
                SHORTCUTS.forEach { (keys, description) ->
                    if (keys.isEmpty() && description.isEmpty()) {
                        Text(text = " ")
                    } else if (description.isEmpty()) {
                        Text(
                            text = keys,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    } else {
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = keys,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.width(140.dp)
                            )
                            Text(
                                text = description,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
        }
    }
}
