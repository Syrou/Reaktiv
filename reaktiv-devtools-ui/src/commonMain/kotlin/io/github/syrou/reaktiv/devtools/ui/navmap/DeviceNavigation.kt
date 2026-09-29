package io.github.syrou.reaktiv.devtools.ui.navmap

import io.github.syrou.reaktiv.devtools.protocol.NavigationEntrySnapshot
import io.github.syrou.reaktiv.devtools.protocol.NavigationSnapshot
import io.github.syrou.reaktiv.devtools.protocol.parseNavigationState
import io.github.syrou.reaktiv.devtools.ui.DevToolsUiState
import io.github.syrou.reaktiv.devtools.ui.Reconstruction
import io.github.syrou.reaktiv.devtools.ui.navigationPositionIndex

internal data class DeviceEntry(val entry: NavigationEntrySnapshot, val belowTop: Int)

internal fun DevToolsUiState.navigationSnapshot(): NavigationSnapshot? {
    val index = navigationPositionIndex
    val stateJson = if (index == null) initialStateJson else Reconstruction.stateAt(initialStateJson, actionStateHistory, index)
    return parseNavigationState(stateJson)
}

internal fun NavigationSnapshot.deviceEntry(path: String): DeviceEntry? {
    val index = backStack.indexOfLast { it.path == path }
    if (index < 0) return null
    return DeviceEntry(backStack[index], backStack.lastIndex - index)
}
