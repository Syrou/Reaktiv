import io.github.syrou.reaktiv.navigation.history.BrowserListener
import io.github.syrou.reaktiv.navigation.history.BrowserPort
import io.github.syrou.reaktiv.navigation.history.BrowserUrl
import io.github.syrou.reaktiv.navigation.history.ClaimResult
import io.github.syrou.reaktiv.navigation.history.PortClaim
import io.github.syrou.reaktiv.navigation.history.WriteResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal class FakeBrowser(
    dispatcher: CoroutineDispatcher,
    initialUrl: String = ORIGIN + "/",
    override val offersTraverseIntents: Boolean = false,
    override val basePath: String = "/"
) : BrowserPort {

    class Entry(val url: String, val state: String?)

    private val scope = CoroutineScope(dispatcher)
    private var claim = PortClaim()
    private val listener: BrowserListener? get() = claim.listener
    private var hasActivation = false

    val entries = mutableListOf(Entry(initialUrl, null))
    var index = 0
        private set
    val log = mutableListOf<String>()
    val session = mutableMapOf<String, String>()
    var throttleWrites = false
    var goDelayMs = 0L
    var losesGoes = false
    var maxStateLength = Int.MAX_VALUE

    override var activationCount = 0L
        private set

    val currentUrl: String get() = entries[index].url
    val currentState: String? get() = entries[index].state
    val writes: Int get() = log.count { it.startsWith("push") || it.startsWith("replace") }

    fun back(steps: Int = 1, uaTransition: Boolean = false) = traverse(-steps, uaTransition)

    fun forward(steps: Int = 1, uaTransition: Boolean = false) = traverse(steps, uaTransition)

    fun traverse(delta: Int, uaTransition: Boolean = false) {
        if (index + delta !in entries.indices) return
        if (offersTraverseIntents) {
            val cancelable = hasActivation
            val cancelled = listener?.onTraverseIntent(delta, cancelable) == true
            if (cancelled && cancelable) {
                hasActivation = false
                return
            }
        }
        index += delta
        listener?.onLanded(uaTransition)
    }

    fun typeUrl(url: String) {
        dropForward()
        entries.add(Entry(resolve(url), null))
        index++
        listener?.onLanded(false)
    }

    fun interact() {
        activationCount++
        hasActivation = true
    }

    fun reload() {
        claim = PortClaim()
    }

    fun corruptCurrentState(state: String?) {
        entries[index] = Entry(currentUrl, state)
    }

    override fun url(): BrowserUrl {
        val href = currentUrl
        val local = href.removePrefix(ORIGIN)
        val beforeHash = local.substringBefore('#')
        val hash = if ('#' in local) "#" + local.substringAfter('#') else ""
        val pathname = beforeHash.substringBefore('?').ifEmpty { "/" }
        val search = if ('?' in beforeHash) "?" + beforeHash.substringAfter('?') else ""
        return BrowserUrl(pathname, search, hash, href)
    }

    override fun entryState(): String? = currentState

    override fun sessionItem(key: String): String? = session[key]

    override fun storeSessionItem(key: String, value: String?) {
        if (value == null) session.remove(key) else session[key] = value
    }

    override fun push(state: String, url: String): WriteResult {
        limit(state)?.let { return it }
        log.add("push $url")
        dropForward()
        entries.add(Entry(resolve(url), state))
        index++
        return WriteResult.Written
    }

    override fun replace(state: String, url: String): WriteResult {
        limit(state)?.let { return it }
        log.add("replace $url")
        entries[index] = Entry(resolve(url), state)
        return WriteResult.Written
    }

    override fun go(delta: Int) {
        log.add("go $delta")
        if (losesGoes) return
        scope.launch {
            delay(goDelayMs)
            if (index + delta in entries.indices) {
                index += delta
                listener?.onLanded(false)
            }
        }
    }

    override fun claim(owner: Any, listener: BrowserListener): ClaimResult = claim.claim(owner, listener)

    override fun release(owner: Any, listener: BrowserListener) = claim.release(owner, listener)

    private fun limit(state: String): WriteResult? = when {
        throttleWrites -> WriteResult.Throttled
        state.length > maxStateLength -> WriteResult.TooLarge
        else -> null
    }

    private fun dropForward() {
        while (entries.size > index + 1) entries.removeAt(entries.lastIndex)
    }

    private fun resolve(url: String): String = when {
        url.startsWith("http") -> url
        url.startsWith("#") -> currentUrl.substringBefore('#') + url
        url.startsWith("/") -> ORIGIN + url
        else -> currentUrl.substringBeforeLast('/') + "/" + url
    }

    companion object {
        const val ORIGIN = "https://app.test"
    }
}
