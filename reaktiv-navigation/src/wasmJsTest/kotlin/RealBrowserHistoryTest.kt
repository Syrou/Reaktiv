import io.github.syrou.reaktiv.core.createStore
import io.github.syrou.reaktiv.navigation.createNavigationModule
import io.github.syrou.reaktiv.navigation.extension.navigation
import io.github.syrou.reaktiv.navigation.history.BrowserHistoryMode
import io.github.syrou.reaktiv.navigation.history.UrlStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals

private fun pageHash(): String = js("window.location.hash")

private fun pagePath(): String = js("window.location.pathname")

private fun pageHistoryLength(): Int = js("window.history.length")

private fun pageBack(): Unit = js("window.history.back()")

private fun pageForward(): Unit = js("window.history.forward()")

private fun pageHref(): String = js("window.location.href")

private fun restorePage(href: String): Unit = js("window.history.replaceState(null, '', href)")

private fun dropForwardEntries(): Unit = js("window.history.pushState(null, '', window.location.href)")

class RealBrowserHistoryTest {

    private val home = historyScreen("home")
    private val a = historyScreen("a")
    private val user = historyScreen("user/{id}")

    private suspend fun settle() {
        withContext(Dispatchers.Default) { delay(150) }
    }

    @Test
    fun `path urls are the default and the browser buttons drive the store`() = runTest {
        val originalHref = pageHref()
        dropForwardEntries()
        val length = pageHistoryLength()
        val store = createStore {
            module(
                createNavigationModule {
                    browserHistory { mode = BrowserHistoryMode.Always }
                    rootGraph {
                        start(home)
                        screens(home, a, user)
                    }
                }
            )
            coroutineContext(Dispatchers.Default)
        }
        try {
            settle()
            assertEquals("/home", pagePath())
            assertEquals("", pageHash())
            assertEquals(length, pageHistoryLength())

            store.navigation { navigateTo("a") }
            store.navigation { navigateTo(user, "id" to "naïve café") }
            settle()
            assertEquals("/user/na%C3%AFve%20caf%C3%A9", pagePath())
            assertEquals(length + 2, pageHistoryLength())

            pageBack()
            settle()
            assertEquals(listOf("home", "a"), store.locations())
            assertEquals("/a", pagePath(), "after browser back")

            pageForward()
            settle()
            assertEquals(listOf("home", "a", "user/na%C3%AFve%20caf%C3%A9"), store.locations())

            store.navigation { navigateBack() }
            settle()
            assertEquals("/a", pagePath(), "after in-app back")
            assertEquals(length + 2, pageHistoryLength())
        } finally {
            store.cleanup()
            settle()
            restorePage(originalHref)
        }
    }

    @Test
    fun `hash urls write real history entries and the browser buttons drive the store`() = runTest {
        val originalHref = pageHref()
        dropForwardEntries()
        val length = pageHistoryLength()
        val store = createStore {
            module(
                createNavigationModule {
                    browserHistory {
                        mode = BrowserHistoryMode.Always
                        urlStyle = UrlStyle.Hash
                    }
                    rootGraph {
                        start(home)
                        screens(home, a, user)
                    }
                }
            )
            coroutineContext(Dispatchers.Default)
        }
        try {
            settle()
            assertEquals("#/home", pageHash())
            assertEquals(length, pageHistoryLength())

            store.navigation { navigateTo("a") }
            store.navigation { navigateTo(user, "id" to "naïve café") }
            settle()
            assertEquals("#/user/na%C3%AFve%20caf%C3%A9", pageHash())
            assertEquals(length + 2, pageHistoryLength())

            pageBack()
            settle()
            assertEquals(listOf("home", "a"), store.locations())
            assertEquals("#/a", pageHash(), "after browser back")

            pageForward()
            settle()
            assertEquals(listOf("home", "a", "user/na%C3%AFve%20caf%C3%A9"), store.locations())

            store.navigation { navigateBack() }
            settle()
            assertEquals("#/a", pageHash(), "after in-app back")
            assertEquals(length + 2, pageHistoryLength())
        } finally {
            store.cleanup()
            settle()
            restorePage(originalHref)
        }
    }
}
