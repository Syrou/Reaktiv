import io.github.syrou.reaktiv.core.createStore
import io.github.syrou.reaktiv.navigation.NavigationAction
import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.TraverseDirection
import io.github.syrou.reaktiv.navigation.TraversePresentation
import io.github.syrou.reaktiv.navigation.createNavigationModule
import io.github.syrou.reaktiv.navigation.extension.navigation
import io.github.syrou.reaktiv.navigation.history.UrlStyle
import io.github.syrou.reaktiv.navigation.model.GuardResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.DurationUnit
import kotlin.time.toDuration

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryInboundTest {

    private val home = historyScreen("home")
    private val a = historyScreen("a")
    private val b = historyScreen("b")
    private val user = historyScreen("user/{id}")
    private val login = historyScreen("login")
    private val dashboard = historyScreen("dashboard")
    private var decision: GuardResult = GuardResult.Allow

    private fun TestScope.store(browser: FakeBrowser) = createStore {
        module(
            createNavigationModule {
                browserHistoryForTesting(browser, UrlStyle.Hash)
                rootGraph {
                    start(home)
                    screens(home, a, b, user, login)
                    intercept(guard = { _ -> decision }) {
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

    private suspend fun TestScope.threeDeep(browser: FakeBrowser) = store(browser).also { store ->
        advanceUntilIdle()
        store.navigation { navigateTo("a") }
        store.navigation { navigateTo("b") }
        advanceUntilIdle()
    }

    private suspend fun io.github.syrou.reaktiv.core.Store.lastAction() =
        selectState<NavigationState>().first().lastNavigationAction

    @Test
    fun `browser back restores the previous stack without writing`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val browser = fakeBrowser()
            val store = threeDeep(browser)
            val writes = browser.writes

            browser.back()
            advanceUntilIdle()

            assertEquals(listOf("home", "a"), store.locations())
            val action = assertIs<NavigationAction.Traverse>(store.lastAction())
            assertEquals(TraverseDirection.Back, action.direction)
            assertEquals(writes, browser.writes)
            assertEquals(3, browser.entries.size)
        }

    @Test
    fun `browser forward restores the next stack`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val browser = fakeBrowser()
            val store = threeDeep(browser)
            browser.back()
            advanceUntilIdle()

            browser.forward()
            advanceUntilIdle()

            assertEquals(listOf("home", "a", "b"), store.locations())
            assertEquals(TraverseDirection.Forward, assertIs<NavigationAction.Traverse>(store.lastAction()).direction)
        }

    @Test
    fun `a jump through the history menu restores that stack`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val browser = fakeBrowser()
            val store = threeDeep(browser)

            browser.back(2)
            advanceUntilIdle()

            assertEquals(listOf("home"), store.locations())
        }

    @Test
    fun `a traversal the browser animated itself is presented at rest`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val browser = fakeBrowser()
            val store = threeDeep(browser)

            browser.back(uaTransition = true)
            advanceUntilIdle()

            val action = assertIs<NavigationAction.Traverse>(store.lastAction())
            assertEquals(TraversePresentation.AlreadyPresented, action.presentation)
        }

    @Test
    fun `rapid backs settle on the last entry`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val browser = fakeBrowser()
            val store = threeDeep(browser)
            store.navigation { navigateTo(user, "id" to 1) }
            store.navigation { navigateTo(user, "id" to 2) }
            advanceUntilIdle()

            repeat(4) { browser.back() }
            advanceUntilIdle()

            assertEquals(listOf("home"), store.locations())
            assertEquals(0, browser.index)
        }

    @Test
    fun `editing the hash navigates and the new entry records the result`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val browser = fakeBrowser()
            val store = store(browser)
            advanceUntilIdle()

            browser.typeUrl("#/user/42")
            advanceUntilIdle()

            assertEquals(listOf("home", "user/42"), store.locations())
            assertEquals(listOf("#/home", "#/user/42"), browser.urls)
            assertEquals(1, store.historyEntry(browser)?.idx)
            assertEquals(listOf("home", "user/{id}"), store.historyEntry(browser)?.snapshot?.entries?.map { it.path })
        }

    @Test
    fun `editing the hash to an unknown route keeps the app where it was`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val browser = fakeBrowser()
            val store = store(browser)
            advanceUntilIdle()
            store.navigation { navigateTo("a") }
            advanceUntilIdle()

            browser.typeUrl("#/no/such/place")
            advanceUntilIdle()

            assertEquals(listOf("home", "a"), store.locations())
            assertEquals("#/a", browser.currentHash)
        }

    @Test
    fun `a reload restores the exact stack and params`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val browser = fakeBrowser()
            val first = store(browser)
            advanceUntilIdle()
            first.navigation {
                navigateTo(user) {
                    put("id", 7)
                    put("ratio", 0.5)
                }
            }
            advanceUntilIdle()
            first.cleanup()
            browser.reload()

            val second = store(browser)
            advanceUntilIdle()

            val state = second.selectState<NavigationState>().first()
            assertEquals(listOf("home", "user/7"), state.backStack.map { it.location })
            assertEquals(0.5, state.currentEntry.params["ratio"])
            assertIs<NavigationAction.Traverse>(state.lastNavigationAction)
            assertEquals(2, browser.entries.size)
        }

    @Test
    fun `a reload whose snapshot no longer decodes restores from the url`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val browser = fakeBrowser()
            val first = store(browser)
            advanceUntilIdle()
            first.navigation { navigateTo(user, "id" to 7) }
            advanceUntilIdle()
            first.cleanup()
            browser.corruptCurrentState(
                """{"reaktiv":1,"i":1,"d":"x","t":[],"s":{"v":1,"e":[{"p":"gone","q":{}}]}}"""
            )
            browser.reload()

            val second = store(browser)
            advanceUntilIdle()

            assertEquals(listOf("home", "user/7"), second.locations())
        }

    @Test
    fun `a shared link lands with its parents while the browser keeps one entry`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val browser = fakeBrowser(FakeBrowser.ORIGIN + "/#/admin/dashboard")
            val store = store(browser)
            advanceUntilIdle()

            assertEquals(listOf("home", "admin/dashboard"), store.locations())
            assertEquals(listOf("#/admin/dashboard"), browser.urls)
        }

    @Test
    fun `forward into a guarded screen after logout lands on the redirect and replaces the entry`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val browser = fakeBrowser()
            val store = store(browser)
            advanceUntilIdle()
            store.navigation { navigateTo("admin") }
            advanceUntilIdle()
            browser.back()
            advanceUntilIdle()
            decision = GuardResult.RedirectTo(login)

            browser.forward()
            advanceUntilIdle()

            assertEquals(listOf("home", "login"), store.locations())
            assertEquals(listOf("#/home", "#/login"), browser.urls)
            assertEquals(1, browser.index)
        }

    @Test
    fun `forward into a screen a guard now rejects returns the browser to where it was`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val browser = fakeBrowser()
            val store = store(browser)
            advanceUntilIdle()
            store.navigation { navigateTo("admin") }
            advanceUntilIdle()
            browser.back()
            advanceUntilIdle()
            decision = GuardResult.Reject

            browser.forward()
            advanceUntilIdle()

            assertEquals(listOf("home"), store.locations())
            assertEquals(0, browser.index)
            assertEquals(listOf("#/home", "#/admin/dashboard"), browser.urls)
        }
}
