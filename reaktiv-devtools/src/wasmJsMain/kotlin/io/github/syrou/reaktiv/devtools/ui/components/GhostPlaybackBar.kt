package io.github.syrou.reaktiv.devtools.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
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
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * Playback control bar for ghost device session replay.
 *
 * Usage:
 * ```kotlin
 * GhostPlaybackBar(
 *     currentPosition = state.ghostPlaybackPosition,
 *     totalEvents = state.actionStateHistory.size,
 *     isPlaying = state.ghostPlaybackEnabled,
 *     onPositionChange = { dispatch(DevToolsAction.SetGhostPlaybackPosition(it)) },
 *     onTogglePlayback = { dispatch(DevToolsAction.ToggleGhostPlayback) },
 *     onClose = { dispatch(DevToolsAction.ToggleGhostPlayback) }
 * )
 * ```
 */
@Composable
fun GhostPlaybackBar(
    currentPosition: Int,
    totalEvents: Int,
    isPlaying: Boolean,
    onPositionChange: (Int) -> Unit,
    onTogglePlayback: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    var playbackSpeed by remember { mutableStateOf(1f) }
    var showSpeedMenu by remember { mutableStateOf(false) }
    var autoPlaying by remember { mutableStateOf(false) }

    LaunchedEffect(autoPlaying, currentPosition, playbackSpeed) {
        if (autoPlaying && currentPosition < totalEvents - 1) {
            delay((1000 / playbackSpeed).toLong())
            onPositionChange(currentPosition + 1)
        } else if (currentPosition >= totalEvents - 1) {
            autoPlaying = false
        }
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Ghost Playback",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )

                    Text(
                        text = "${currentPosition + 1} / $totalEvents",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.7f)
                    )
                }

                IconButton(onClick = onClose) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close playback",
                        tint = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (totalEvents > 1) {
                Slider(
                    value = currentPosition.toFloat(),
                    onValueChange = { onPositionChange(it.toInt()) },
                    valueRange = 0f..(totalEvents - 1).toFloat(),
                    steps = if (totalEvents > 2) totalEvents - 2 else 0,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(8.dp))
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = { onPositionChange(0) },
                    enabled = currentPosition > 0
                ) {
                    Icon(
                        imageVector = Icons.Default.SkipPrevious,
                        contentDescription = "Go to start",
                        tint = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                }

                IconButton(
                    onClick = { onPositionChange((currentPosition - 10).coerceAtLeast(0)) },
                    enabled = currentPosition > 0
                ) {
                    Icon(
                        imageVector = Icons.Default.FastRewind,
                        contentDescription = "Rewind 10",
                        tint = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                }

                IconButton(
                    onClick = { autoPlaying = !autoPlaying }
                ) {
                    Icon(
                        imageVector = if (autoPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (autoPlaying) "Pause" else "Play",
                        tint = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                }

                IconButton(
                    onClick = { onPositionChange((currentPosition + 10).coerceAtMost(totalEvents - 1)) },
                    enabled = currentPosition < totalEvents - 1
                ) {
                    Icon(
                        imageVector = Icons.Default.FastForward,
                        contentDescription = "Forward 10",
                        tint = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                }

                IconButton(
                    onClick = { onPositionChange(totalEvents - 1) },
                    enabled = currentPosition < totalEvents - 1
                ) {
                    Icon(
                        imageVector = Icons.Default.SkipNext,
                        contentDescription = "Go to end",
                        tint = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                }

                Spacer(modifier = Modifier.width(16.dp))

                TextButton(onClick = { showSpeedMenu = true }) {
                    Text(
                        text = "${playbackSpeed}x",
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )

                    DropdownMenu(
                        expanded = showSpeedMenu,
                        onDismissRequest = { showSpeedMenu = false }
                    ) {
                        listOf(0.5f, 1f, 2f, 5f, 10f).forEach { speed ->
                            DropdownMenuItem(
                                text = { Text("${speed}x") },
                                onClick = {
                                    playbackSpeed = speed
                                    showSpeedMenu = false
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
