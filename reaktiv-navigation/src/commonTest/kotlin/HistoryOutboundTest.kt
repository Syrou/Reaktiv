import io.github.syrou.reaktiv.core.createStore
import io.github.syrou.reaktiv.navigation.createNavigationModule
import io.github.syrou.reaktiv.navigation.extension.navigation
import io.github.syrou.reaktiv.navigation.history.UrlStyle
import io.github.syrou.reaktiv.navigation.layer.RenderLayer
import io.github.syrou.reaktiv.navigation.model.GuardResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.DurationUnit
import kotlin.time.toDuration

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryOutboundTest {

    private val home = historyScreen("home")
    private val a = historyScreen("a")
    private val b = historyScreen("b")
    private val user = historyScreen("user/{id}")
    private val login = historyScreen("login")
    private val dashboard = historyScreen("dashboard")
    private val alert = historyModal("alert", RenderLayer.SYSTEM)
    private var allowed = false

    private fun TestScope.store(browser: FakeBrowser) = createStore {
        module(
            createNavigationModule {
                browserHistoryForTesting(browser, UrlStyle.Hash)
                rootGraph {
                    start(home)
                    screens(home, a, b, user, login)
                    modals(alert)
                    intercept(guard = { _ -> if (allowed) GuardResult.Allow else GuardResult.PendAndRedirectTo(login) }) {
                        graph("admin") {
                            start(dashboard)
                            screens(dashboard)
                        }
                    }
                }
            }
        )
        coroutineContext(StandardTestDispatcher(testScheduler))
    }

    @Test
    fun `startup replaces the first entry with the start screen`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val browser = fakeBrowser()
            val store = store(browser)
            advanceUntilIdle()

            assertEquals(listOf("#/home"), browser.urls)
            assertEquals(listOf("replace #/home"), browser.log)
            assertEquals(listOf("home"), store.historyEntry(browser)?.snapshot?.entries?.map { it.path })
        }

    @Test
    fun `each navigation pushes an entry that names the screen`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val browser = fakeBrowser()
            val store = store(browser)
            advanceUntilIdle()

            store.navigation { navigateTo("a") }
            store.navigation { navigateTo(user, "id" to 7) }
            advanceUntilIdle()

            assertEquals(listOf("#/home", "#/a", "#/user/7"), browser.urls)
            assertEquals(2, browser.index)
            assertEquals(2, store.historyEntry(browser)?.idx)
        }

    @Test
    fun `in-app back traverses the browser history so forward stays available`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val browser = fakeBrowser()
            val store = store(browser)
            advanceUntilIdle()
            store.navigation { navigateTo("a") }
            store.navigation { navigateTo("b") }
            advanceUntilIdle()
            val writes = browser.writes

            store.navigation { navigateBack() }
            advanceUntilIdle()

            assertEquals("go -1", browser.log.last())
            assertEquals(1, browser.index)
            assertEquals(3, browser.entries.size)
            assertEquals(writes, browser.writes)
            assertEquals(listOf("home", "a"), store.locations())
        }

    @Test
    fun `a traversal the browser never completes is given up without traversing again`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val browser = fakeBrowser()
            val store = store(browser)
            advanceUntilIdle()
            store.navigation { navigateTo("a") }
            store.navigation { navigateTo("b") }
            advanceUntilIdle()
            browser.losesGoes = true

            store.navigation { navigateBack() }
            advanceUntilIdle()

            assertEquals(1, browser.log.count { it.startsWith("go") })
            assertEquals(2, browser.index)
            assertEquals("#/a", browser.currentHash)
            assertEquals(listOf("home", "a"), store.locations())
        }

    @Test
    fun `popping to an earlier screen goes back as many entries`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val browser = fakeBrowser()
            val store = store(browser)
            advanceUntilIdle()
            store.navigation { navigateTo("a") }
            store.navigation { navigateTo("b") }
            advanceUntilIdle()

            store.navigation { popUpTo("home") }
            advanceUntilIdle()

            assertEquals("go -2", browser.log.last())
            assertEquals(0, browser.index)
        }

    @Test
    fun `in-app back to a parent the browser never visited pushes it`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val browser = fakeBrowser(FakeBrowser.ORIGIN + "/#/user/7")
            val store = store(browser)
            advanceUntilIdle()
            assertEquals(listOf("home", "user/7"), store.locations())
            assertEquals(listOf("#/user/7"), browser.urls)

            store.navigation { navigateBack() }
            advanceUntilIdle()

            assertEquals(listOf("#/user/7", "#/home"), browser.urls)
        }

    @Test
    fun `a replacing navigation replaces the entry`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val browser = fakeBrowser()
            val store = store(browser)
            advanceUntilIdle()
            store.navigation { navigateTo("a") }
            advanceUntilIdle()

            store.navigation { navigateTo("b", replaceCurrent = true) }
            advanceUntilIdle()

            assertEquals(listOf("#/home", "#/b"), browser.urls)
        }

    @Test
    fun `completing a pending navigation replaces the login entry`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val browser = fakeBrowser()
            val store = store(browser)
            advanceUntilIdle()
            store.navigation { navigateTo("admin") }
            advanceUntilIdle()
            assertEquals(listOf("#/home", "#/login"), browser.urls)

            allowed = true
            store.navigation {
                clearBackStack()
                resumePendingNavigation()
            }
            advanceUntilIdle()

            assertEquals(listOf("#/home", "#/admin/dashboard"), browser.urls)
        }

    @Test
    fun `clearing the back stack pushes`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val browser = fakeBrowser()
            val store = store(browser)
            advanceUntilIdle()

            store.navigation {
                clearBackStack()
                navigateTo("a")
            }
            advanceUntilIdle()

            assertEquals(listOf("#/home", "#/a"), browser.urls)
        }

    @Test
    fun `each entry remembers the digests of up to 32 entries behind it`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val browser = fakeBrowser()
            val store = store(browser)
            advanceUntilIdle()

            for (id in 1..40) store.navigation { navigateTo(user, "id" to id) }
            advanceUntilIdle()

            val top = assertNotNull(store.historyEntry(browser))
            val previous = assertNotNull(store.historyEntry(browser, browser.index - 1))
            assertEquals(32, top.trail.size)
            assertEquals(previous.digest, top.trail.last())
            assertEquals(previous.trail.drop(1) + previous.digest, top.trail)
        }

    @Test
    fun `a commit that leaves the addressable stack unchanged writes nothing`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val browser = fakeBrowser()
            val store = store(browser)
            advanceUntilIdle()
            val writes = browser.writes

            store.navigation { navigateTo(alert) }
            advanceUntilIdle()

            assertEquals(writes, browser.writes)
        }

    @Test
    fun `rapid replaces are coalesced into one write`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val browser = fakeBrowser()
            val store = store(browser)
            runCurrent()

            for (id in 1..10) store.navigation { navigateTo(user, "id" to id, replaceCurrent = true) }
            advanceUntilIdle()

            assertEquals(2, browser.log.count { it.startsWith("replace") })
            assertEquals("#/user/10", browser.currentHash)
        }

    @Test
    fun `an entry too large for the browser keeps only its url`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val browser = fakeBrowser()
            browser.maxStateLength = 70
            val store = store(browser)
            advanceUntilIdle()

            val entry = assertNotNull(store.historyEntry(browser))
            assertNull(entry.snapshot)
            assertEquals("#/home", browser.currentHash)
        }

    @Test
    fun `a throttled write is retried`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val browser = fakeBrowser()
            browser.throttleWrites = true
            val store = store(browser)
            runCurrent()
            advanceTimeBy(100)
            assertNull(browser.currentState)

            browser.throttleWrites = false
            advanceUntilIdle()

            assertEquals("#/home", browser.currentHash)
            assertNotNull(store.historyEntry(browser)?.snapshot)
        }
}
