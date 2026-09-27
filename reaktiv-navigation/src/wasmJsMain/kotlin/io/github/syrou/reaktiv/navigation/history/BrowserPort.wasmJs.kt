package io.github.syrou.reaktiv.navigation.history

private val windowPort: HistoryApiPort? by lazy { currentWindow()?.let(::HistoryApiPort) }

internal actual fun platformBrowserPort(mode: BrowserHistoryMode): BrowserPort? {
    val win = currentWindow() ?: return null
    return when (mode) {
        BrowserHistoryMode.Off -> null
        BrowserHistoryMode.TopLevelOnly -> if (isTopLevelWindow(win)) windowPort else null
        BrowserHistoryMode.Always -> windowPort
    }
}
