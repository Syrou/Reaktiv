import io.github.syrou.reaktiv.core.createStore
import io.github.syrou.reaktiv.navigation.createNavigationModule
import io.github.syrou.reaktiv.navigation.definition.DismissAction
import io.github.syrou.reaktiv.navigation.definition.Dismissal
import io.github.syrou.reaktiv.navigation.extension.navigation
import io.github.syrou.reaktiv.navigation.history.UrlStyle
import io.github.syrou.reaktiv.navigation.layer.RenderLayer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.DurationUnit
import kotlin.time.toDuration

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryRefusalTest {

    private var handled = 0
    private val home = historyScreen("home")
    private val a = historyScreen("a")
    private val blocking = historyScreen("blocking", Dismissal.Blocking)
    private val confirming = historyScreen("confirming", Dismissal(back = DismissAction.Run { handled++ }))
    private val alert = historyModal("alert", RenderLayer.SYSTEM)

    private fun TestScope.store(browser: FakeBrowser) = createStore {
        module(
            createNavigationModule {
                browserHistoryForTesting(browser, UrlStyle.Hash)
                rootGraph {
                    start(home)
                    screens(home, a, blocking, confirming)
                    modals(alert)
                }
            }
        )
        coroutineContext(StandardTestDispatcher(testScheduler))
    }

    private suspend fun TestScope.onTopOf(browser: FakeBrowser, route: String) = store(browser).also { store ->
        advanceUntilIdle()
        store.navigation { navigateTo("a") }
        store.navigation { navigateTo(route) }
        advanceUntilIdle()
    }

    @Test
    fun `a screen that ignores back holds the browser where it is`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val browser = fakeBrowser()
            val store = onTopOf(browser, "blocking")

            browser.interact()
            browser.back()
            advanceUntilIdle()

            assertEquals(listOf("home", "a", "blocking"), store.locations())
            assertEquals(2, browser.index)
            assertEquals("go 1", browser.log.last())
        }

    @Test
    fun `a second back without any interaction in between goes through`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val browser = fakeBrowser()
            val store = onTopOf(browser, "blocking")
            browser.interact()
            browser.back()
            advanceUntilIdle()

            browser.back()
            advanceUntilIdle()

            assertEquals(listOf("home", "a"), store.locations())
            assertEquals(1, browser.index)
        }

    @Test
    fun `a screen that runs a handler on back runs it instead of leaving`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val browser = fakeBrowser()
            val store = onTopOf(browser, "confirming")

            browser.interact()
            browser.back()
            advanceUntilIdle()

            assertEquals(1, handled)
            assertEquals(listOf("home", "a", "confirming"), store.locations())
            assertEquals(2, browser.index)
        }

    @Test
    fun `where the browser offers a cancelable traversal a refused back never happens`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val browser = fakeBrowser(offersTraverseIntents = true)
            val store = onTopOf(browser, "blocking")

            browser.interact()
            browser.back()
            advanceUntilIdle()

            assertEquals(listOf("home", "a", "blocking"), store.locations())
            assertEquals(2, browser.index)
            assertTrue(browser.log.none { it.startsWith("go") })

            browser.back()
            advanceUntilIdle()

            assertEquals(listOf("home", "a"), store.locations())
        }

    @Test
    fun `back with a system alert on top dismisses only the alert`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val browser = fakeBrowser()
            val store = onTopOf(browser, "a")
            store.navigation { navigateTo(alert) }
            advanceUntilIdle()
            assertEquals(listOf("home", "a", "alert"), store.locations())

            browser.interact()
            browser.back()
            advanceUntilIdle()

            assertEquals(listOf("home", "a"), store.locations())
            assertEquals(1, browser.index)
            assertEquals("#/a", browser.currentHash)
        }
}
