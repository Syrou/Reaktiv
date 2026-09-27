import io.github.syrou.reaktiv.core.createStore
import io.github.syrou.reaktiv.navigation.createNavigationModule
import io.github.syrou.reaktiv.navigation.extension.navigation
import io.github.syrou.reaktiv.navigation.history.BrowserHistoryMode
import io.github.syrou.reaktiv.navigation.history.platformBrowserPort
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private fun pageHistoryLength(): Int = js("window.history.length")

private fun pageHash(): String = js("window.location.hash")

class KarmaIsolationTest {

    private val home = historyScreen("home")
    private val a = historyScreen("a")

    @Test
    fun `the default mode stays out of the iframe the test runner uses`() {
        assertNull(platformBrowserPort(BrowserHistoryMode.TopLevelOnly))
    }

    @Test
    fun `a store with default settings writes nothing to the page history`() = runTest {
        val length = pageHistoryLength()
        val hash = pageHash()
        val store = createStore {
            module(
                createNavigationModule {
                    rootGraph {
                        start(home)
                        screens(home, a)
                    }
                }
            )
            coroutineContext(StandardTestDispatcher(testScheduler))
        }
        advanceUntilIdle()

        store.navigation { navigateTo("a") }
        advanceUntilIdle()

        assertEquals(length, pageHistoryLength())
        assertEquals(hash, pageHash())
    }
}
