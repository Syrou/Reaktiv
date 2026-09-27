package io.github.syrou.reaktiv.navigation.history

internal data class BrowserUrl(val pathname: String, val search: String, val hash: String, val href: String)

internal enum class WriteResult { Written, Throttled, TooLarge, Failed }

internal sealed class ClaimResult {
    data object Bound : ClaimResult()
    data class Rebound(val previous: BrowserListener) : ClaimResult()
    data object Denied : ClaimResult()
}

internal interface BrowserListener {
    fun onTraverseIntent(delta: Int, cancelable: Boolean): Boolean

    fun onLanded(uaTransition: Boolean)
}

internal interface BrowserPort {
    val offersTraverseIntents: Boolean

    val basePath: String

    val activationCount: Long

    fun url(): BrowserUrl

    fun entryState(): String?

    fun push(state: String, url: String): WriteResult

    fun replace(state: String, url: String): WriteResult

    fun go(delta: Int)

    fun sessionItem(key: String): String?

    fun storeSessionItem(key: String, value: String?)

    fun claim(owner: Any, listener: BrowserListener): ClaimResult

    fun release(owner: Any, listener: BrowserListener)
}

internal class BrowserHistorySetup(
    private val portProvider: () -> BrowserPort?,
    val style: UrlStyle = UrlStyle.Path,
    val basePath: String? = null,
    val mode: BrowserHistoryMode = BrowserHistoryMode.TopLevelOnly,
    val documentTitle: (String?) -> String? = { it }
) {
    val port: BrowserPort? by lazy(portProvider)

    val isAvailable: Boolean get() = port != null

    val webLocation: WebLocation? by lazy { port?.let { WebLocation(style, basePath ?: it.basePath) } }
}

internal expect fun platformBrowserPort(mode: BrowserHistoryMode): BrowserPort?
