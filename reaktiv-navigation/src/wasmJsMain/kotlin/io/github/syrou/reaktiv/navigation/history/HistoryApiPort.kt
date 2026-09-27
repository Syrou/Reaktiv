package io.github.syrou.reaktiv.navigation.history

internal class HistoryApiPort(private val win: JsAny) : BrowserPort {

    private val claim = PortClaim()

    override val offersTraverseIntents: Boolean = hasNavigationApi(win)

    override val basePath: String = documentBasePath(win)

    override var activationCount: Long = 0L
        private set

    init {
        useManualScrollRestoration(win)
        installHistoryListeners(
            win,
            onLanded = { uaTransition -> claim.listener?.onLanded(uaTransition) },
            onIntent = { delta, cancelable -> claim.listener?.onTraverseIntent(delta, cancelable) ?: false },
            onActivation = { activationCount++ }
        )
    }

    override fun url(): BrowserUrl = BrowserUrl(
        pathname = locationPart(win, "pathname"),
        search = locationPart(win, "search"),
        hash = locationPart(win, "hash"),
        href = locationPart(win, "href")
    )

    override fun entryState(): String? = historyStateText(win)

    override fun push(state: String, url: String): WriteResult = write(replace = false, state, url)

    override fun replace(state: String, url: String): WriteResult = write(replace = true, state, url)

    override fun go(delta: Int) {
        historyGo(win, delta)
    }

    override fun sessionItem(key: String): String? = sessionGet(win, key)

    override fun storeSessionItem(key: String, value: String?) {
        sessionSet(win, key, value)
    }

    override fun claim(owner: Any, listener: BrowserListener): ClaimResult = claim.claim(owner, listener)

    override fun release(owner: Any, listener: BrowserListener) {
        claim.release(owner, listener)
    }

    private fun write(replace: Boolean, state: String, url: String): WriteResult =
        when (historyWrite(win, replace, state, url)) {
            "written" -> WriteResult.Written
            "throttled" -> WriteResult.Throttled
            "too-large" -> WriteResult.TooLarge
            else -> WriteResult.Failed
        }
}
