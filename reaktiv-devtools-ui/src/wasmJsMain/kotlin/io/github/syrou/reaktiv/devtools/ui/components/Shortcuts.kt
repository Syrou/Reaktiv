package io.github.syrou.reaktiv.devtools.ui.components

import androidx.compose.ui.input.key.Key

internal enum class Shortcut(val keys: List<Key>, val label: String, val description: String) {
    SEARCH(listOf(Key.Slash), "/", "Search this list"),
    HELP(listOf(Key.Slash), "?", "This help"),
    TIME_TRAVEL(listOf(Key.T), "t", "Toggle time travel"),
    PLAYBACK(listOf(Key.Spacebar), "Space", "Play or pause playback"),
    PREVIOUS(listOf(Key.J, Key.DirectionLeft), "j / Left", "Step to previous action"),
    NEXT(listOf(Key.K, Key.DirectionRight), "k / Right", "Step to next action"),
    MARKER(listOf(Key.M), "m", "Drop a marker on the publisher"),
    IMPORT_GHOST(listOf(Key.G), "g", "Import a ghost session"),
    EXPORT_SESSION(listOf(Key.E), "e", "Export the current session"),
    DEVICES(listOf(Key.D), "d", "Devices");

    companion object {
        fun of(key: Key, shift: Boolean): Shortcut? = when {
            key == Key.Slash -> if (shift) HELP else SEARCH
            else -> entries.firstOrNull { key in it.keys }
        }
    }
}
