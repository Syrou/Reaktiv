package io.github.syrou.reaktiv.devtools.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

private const val CONFIRM_MS = 2000L

internal data class CopyAction(val label: String, val value: () -> String)

@Composable
internal fun CopyControl(
    actions: List<CopyAction>,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
    dense: Boolean = false,
    showLabel: Boolean = false,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    if (actions.isEmpty()) return

    val buttonSize = if (dense) 20.dp else 26.dp
    val iconSize = if (dense) 13.dp else 15.dp
    var expanded by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(copied) {
        if (copied != null) {
            delay(CONFIRM_MS)
            copied = null
        }
    }

    fun run(action: CopyAction) {
        copyTextToClipboard(action.value())
        copied = action.label
        expanded = false
    }

    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        IconButton(
            onClick = { if (actions.size == 1) run(actions[0]) else expanded = true },
            enabled = enabled,
            modifier = Modifier.size(buttonSize)
        ) {
            if (copied != null) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = "Copied ${copied}",
                    modifier = Modifier.size(iconSize),
                    tint = MaterialTheme.colorScheme.primary
                )
            } else {
                Icon(
                    Icons.Default.ContentCopy,
                    contentDescription = if (actions.size == 1) "Copy ${actions[0].label}" else "Copy",
                    modifier = Modifier.size(iconSize),
                    tint = tint
                )
            }
        }

        if (showLabel && copied != null) {
            Text(
                text = "Copied ${copied}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 4.dp)
            )
        }

        if (expanded) {
            DropdownMenu(expanded = true, onDismissRequest = { expanded = false }) {
                actions.forEach { action ->
                    DropdownMenuItem(
                        text = { Text(action.label) },
                        onClick = { run(action) }
                    )
                }
            }
        }
    }
}

internal fun treeRowCopyActions(
    path: String? = null,
    key: String? = null,
    value: String? = null,
    subtreeJson: (() -> String)? = null,
    wholeLine: (() -> String)? = null
): List<CopyAction> = buildList {
    if (path != null) add(CopyAction("path") { path })
    if (value != null) add(CopyAction("value") { value })
    if (key != null && value != null) add(CopyAction("key and value") { "$key: $value" })
    if (subtreeJson != null) add(CopyAction("subtree as JSON", subtreeJson))
    if (wholeLine != null) add(CopyAction("line", wholeLine))
}
