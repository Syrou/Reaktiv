import io.github.syrou.reaktiv.navigation.history.BrowserListener
import io.github.syrou.reaktiv.navigation.history.HistoryApiPort
import io.github.syrou.reaktiv.navigation.history.WriteResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun fakeWindow(failWith: String, withNavigationApi: Boolean, startUrl: String): JsAny = js("""
    (function(errorName, navigationApi, startUrl) {
        var listeners = {};
        var navListeners = [];
        var w = {
            index: 0,
            entries: [{ url: startUrl, state: null }],
            listeners: listeners,
            goCalls: [],
            prevented: 0,
            document: { baseURI: 'https://app.test/app/index.html' },
            addEventListener: function(type, fn) { (listeners[type] = listeners[type] || []).push(fn); }
        };
        function resolve(u) {
            return new URL(u, w.document.baseURI).href;
        }
        function refuse() {
            if (errorName) {
                var error = new Error('refused');
                error.name = errorName;
                throw error;
            }
        }
        w.history = {
            scrollRestoration: 'auto',
            pushState: function(s, t, u) {
                refuse();
                w.entries.splice(w.index + 1);
                w.entries.push({ url: resolve(u), state: s });
                w.index++;
            },
            replaceState: function(s, t, u) {
                refuse();
                w.entries[w.index] = { url: resolve(u), state: s };
            },
            go: function(d) { w.goCalls.push(d); }
        };
        Object.defineProperty(w.history, 'state', { get: function() { return w.entries[w.index].state; } });
        w.location = {};
        ['href', 'pathname', 'search', 'hash'].forEach(function(p) {
            Object.defineProperty(w.location, p, { get: function() { return new URL(w.entries[w.index].url)[p]; } });
        });
        if (navigationApi) {
            w.navigation = {
                currentEntry: { index: 1 },
                addEventListener: function(type, fn) { if (type === 'navigate') navListeners.push(fn); }
            };
            w.fireNavigate = function(to, cancelable) {
                var event = {
                    navigationType: 'traverse',
                    userInitiated: true,
                    cancelable: cancelable,
                    hasUAVisualTransition: false,
                    destination: { index: to, sameDocument: true },
                    preventDefault: function() { w.prevented++; }
                };
                navListeners.forEach(function(fn) { fn(event); });
            };
        }
        w.fire = function(type, event) {
            (listeners[type] || []).forEach(function(fn) { fn(event || {}); });
        };
        w.top = w;
        return w;
    })(failWith, withNavigationApi, startUrl)
""")

private fun entryCount(w: JsAny): Int = js("w.entries.length")

private fun currentHref(w: JsAny): String = js("w.entries[w.index].url")

private fun scrollRestoration(w: JsAny): String = js("w.history.scrollRestoration")

private fun goCalls(w: JsAny): String = js("w.goCalls.join(',')")

private fun prevented(w: JsAny): Int = js("w.prevented")

private fun traverse(w: JsAny, delta: Int, uaTransition: Boolean): Unit = js("""
    (function() {
        w.index += delta;
        w.fire('popstate', { state: w.entries[w.index].state, hasUAVisualTransition: uaTransition });
        w.fire('hashchange', {});
    })()
""")

private fun editHashWithoutPopstate(w: JsAny, hash: String): Unit = js("""
    (function() {
        w.entries.splice(w.index + 1);
        w.entries.push({ url: w.entries[w.index].url.split('#')[0] + hash, state: null });
        w.index++;
        w.fire('hashchange', {});
    })()
""")

private fun press(w: JsAny): Unit = js("w.fire('pointerdown', {})")

private fun navigate(w: JsAny, to: Int, cancelable: Boolean): Unit = js("w.fireNavigate(to, cancelable)")

private class RecordingListener(private val cancel: Boolean = false) : BrowserListener {
    val landings = mutableListOf<Boolean>()
    val intents = mutableListOf<Pair<Int, Boolean>>()

    override fun onTraverseIntent(delta: Int, cancelable: Boolean): Boolean {
        intents += delta to cancelable
        return cancel
    }

    override fun onLanded(uaTransition: Boolean) {
        landings += uaTransition
    }
}

class BrowserInteropTest {

    private fun port(
        failWith: String = "",
        withNavigationApi: Boolean = false,
        startUrl: String = "https://app.test/app/"
    ): Pair<JsAny, HistoryApiPort> {
        val window = fakeWindow(failWith, withNavigationApi, startUrl)
        return window to HistoryApiPort(window)
    }

    @Test
    fun `writes go through the history api`() {
        val (window, port) = port()

        assertEquals(WriteResult.Written, port.push("first", "#/a"))
        assertEquals(WriteResult.Written, port.replace("second", "#/b"))

        assertEquals(2, entryCount(window))
        assertEquals("https://app.test/app/#/b", currentHref(window))
        assertEquals("second", port.entryState())
        assertEquals("#/b", port.url().hash)
        assertEquals("/app/", port.url().pathname)
    }

    @Test
    fun `a hash write keeps the query the page was opened with`() {
        val (window, port) = port(startUrl = "https://app.test/app/?devtools=localhost%3A8090")

        port.push("first", "#/a")
        port.replace("second", "#/b")

        assertEquals("https://app.test/app/?devtools=localhost%3A8090#/b", currentHref(window))
    }

    @Test
    fun `the base path comes from the document base uri`() {
        val (_, port) = port()

        assertEquals("/app/index.html", port.basePath)
    }

    @Test
    fun `a refused write is reported by its cause`() {
        assertEquals(WriteResult.Throttled, port("SecurityError").second.push("s", "#/a"))
        assertEquals(WriteResult.TooLarge, port("DataCloneError").second.push("s", "#/a"))
        assertEquals(WriteResult.Failed, port("TypeError").second.replace("s", "#/a"))
    }

    @Test
    fun `scroll restoration is left to the app`() {
        val (window, _) = port()

        assertEquals("manual", scrollRestoration(window))
    }

    @Test
    fun `a traversal reaches the listener once even when the hash changes`() {
        val (window, port) = port()
        val listener = RecordingListener()
        port.claim(this, listener)
        port.push("a", "#/a")

        traverse(window, -1, uaTransition = true)

        assertEquals(listOf(true), listener.landings)
    }

    @Test
    fun `a hash edit reported only through hashchange still lands`() {
        val (window, port) = port()
        val listener = RecordingListener()
        port.claim(this, listener)

        editHashWithoutPopstate(window, "#/typed")

        assertEquals(listOf(false), listener.landings)
    }

    @Test
    fun `presses count as user activation`() {
        val (window, port) = port()

        press(window)
        press(window)

        assertEquals(2L, port.activationCount)
    }

    @Test
    fun `go reaches the history api`() {
        val (window, port) = port()

        port.go(-2)

        assertEquals("-2", goCalls(window))
    }

    @Test
    fun `without the navigation api no traversal intents are offered`() {
        val (_, port) = port()

        assertFalse(port.offersTraverseIntents)
    }

    @Test
    fun `a user traversal is offered as an intent and a refusal cancels it`() {
        val (window, port) = port(withNavigationApi = true)
        val listener = RecordingListener(cancel = true)
        port.claim(this, listener)

        navigate(window, to = 0, cancelable = true)

        assertTrue(port.offersTraverseIntents)
        assertEquals(listOf(-1 to true), listener.intents)
        assertEquals(1, prevented(window))
    }
}
