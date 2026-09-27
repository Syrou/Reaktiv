import io.github.syrou.reaktiv.core.ExperimentalReaktivApi
import io.github.syrou.reaktiv.core.ExternalStatePolicy
import io.github.syrou.reaktiv.core.createStore
import io.github.syrou.reaktiv.navigation.createNavigationModule
import io.github.syrou.reaktiv.navigation.extension.navigation
import io.github.syrou.reaktiv.navigation.history.UrlStyle
import io.github.syrou.reaktiv.navigation.model.GuardResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.DurationUnit
import kotlin.time.toDuration

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalReaktivApi::class)
class HistoryClaimTest {

    private val home = historyScreen("home")
    private val a = historyScreen("a")
    private val b = historyScreen("b")
    private val dashboard = historyScreen("dashboard")
    private var guardGate: CompletableDeferred<Unit>? = null

    private fun TestScope.store(browser: FakeBrowser) = createStore {
        module(
            createNavigationModule {
                browserHistoryForTesting(browser, UrlStyle.Hash)
                rootGraph {
                    start(home)
                    screens(home, a, b)
                    intercept(guard = { _ ->
                        guardGate?.await()
                        GuardResult.Allow
                    }) {
                        graph("admin") {
                            start(dashboard)
                            screens(dashboard)
                        }
                    }
                }
            }
        )
        externalState(ExternalStatePolicy.Allow)
        coroutineContext(StandardTestDispatcher(testScheduler))
    }

    @Test
    fun `a second store on the same page runs without browser history`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val browser = fakeBrowser()
            val first = store(browser)
            val second = store(browser)
            advanceUntilIdle()

            second.navigation { navigateTo("a") }
            advanceUntilIdle()
            assertEquals(listOf("#/home"), browser.urls)

            first.navigation { navigateTo("b") }
            advanceUntilIdle()
            assertEquals(listOf("#/home", "#/b"), browser.urls)
        }

    @Test
    fun `a store that is cleaned up releases the page`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val browser = fakeBrowser()
            val first = store(browser)
            advanceUntilIdle()
            first.cleanup()
            advanceUntilIdle()

            val second = store(browser)
            advanceUntilIdle()
            second.navigation { navigateTo("a") }
            advanceUntilIdle()

            assertEquals(listOf("#/home", "#/a"), browser.urls)
        }

    @Test
    fun `a reset lands on the start screen and replaces the current entry`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val browser = fakeBrowser()
            val store = store(browser)
            advanceUntilIdle()
            store.navigation { navigateTo("a") }
            store.navigation { navigateTo("b") }
            advanceUntilIdle()

            store.reset()
            advanceUntilIdle()

            assertEquals(listOf("home"), store.locations())
            assertEquals(listOf("#/home", "#/a", "#/home"), browser.urls)
            assertEquals(2, browser.index)

            store.navigation { navigateTo("a") }
            advanceUntilIdle()
            assertEquals(listOf("#/home", "#/a", "#/home", "#/a"), browser.urls)
        }

    @Test
    fun `a reset while the browser is still going back lands on the start screen where it arrives`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val browser = fakeBrowser()
            browser.goDelayMs = 100
            val store = store(browser)
            advanceUntilIdle()
            store.navigation { navigateTo("a") }
            store.navigation { navigateTo("b") }
            advanceUntilIdle()

            store.navigation { navigateBack() }
            runCurrent()
            assertEquals("go -1", browser.log.last())
            store.reset()
            advanceUntilIdle()

            assertEquals(listOf("home"), store.locations())
            assertEquals(1, browser.index)
            assertEquals("#/home", browser.currentHash)
        }

    @Test
    fun `a reset while a restore waits on a guard lands on the start screen`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val browser = fakeBrowser()
            val store = store(browser)
            advanceUntilIdle()
            store.navigation { navigateTo("admin") }
            advanceUntilIdle()
            browser.back()
            advanceUntilIdle()
            val gate = CompletableDeferred<Unit>()
            guardGate = gate

            browser.forward()
            runCurrent()
            store.reset()
            gate.complete(Unit)
            advanceUntilIdle()

            assertEquals(listOf("home"), store.locations())
            assertEquals(1, browser.index)
            assertEquals("#/home", browser.currentHash)
        }

    @Test
    fun `a followed store neither writes nor follows the browser`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val browser = fakeBrowser()
            val store = store(browser)
            advanceUntilIdle()
            store.navigation { navigateTo("a") }
            advanceUntilIdle()
            val writes = browser.writes

            store.externalState()!!.beginControl()
            browser.back()
            advanceUntilIdle()

            assertEquals(listOf("home", "a"), store.locations())
            assertEquals(writes, browser.writes)
        }
}
