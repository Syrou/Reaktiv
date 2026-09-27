import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import io.github.syrou.reaktiv.core.createStore
import io.github.syrou.reaktiv.navigation.NavigationAction
import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.createNavigationModule
import io.github.syrou.reaktiv.navigation.definition.LoadingModal
import io.github.syrou.reaktiv.navigation.definition.NavigationNode
import io.github.syrou.reaktiv.navigation.extension.navigation
import io.github.syrou.reaktiv.navigation.history.UrlStyle
import io.github.syrou.reaktiv.navigation.layer.RenderLayer
import io.github.syrou.reaktiv.navigation.model.GuardResult
import io.github.syrou.reaktiv.navigation.param.Params
import io.github.syrou.reaktiv.navigation.transition.NavTransition
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.DurationUnit
import kotlin.time.toDuration

@OptIn(ExperimentalCoroutinesApi::class)
class ColdStartTest {

    private val loading = object : LoadingModal {
        override val route = "loading"
        override val enterTransition = NavTransition.None
        override val exitTransition = NavTransition.None

        @Composable
        override fun Content(params: Params) {
            Text("loading")
        }
    }

    private val home = historyScreen("home")
    private val a = historyScreen("a")
    private val login = historyScreen("login")
    private val missing = historyScreen("missing")
    private val dashboard = historyScreen("dashboard")
    private val alert = historyModal("alert", RenderLayer.SYSTEM)
    private var decision: GuardResult = GuardResult.Allow
    private var guardGate: CompletableDeferred<Unit>? = null

    private fun TestScope.store(
        browser: FakeBrowser?,
        withNotFound: Boolean = false,
        dynamicStart: (suspend () -> NavigationNode)? = null
    ) = createStore {
        module(
            createNavigationModule {
                if (browser != null) browserHistoryForTesting(browser, UrlStyle.Hash)
                loadingModal(loading)
                if (withNotFound) notFoundScreen(missing)
                rootGraph {
                    if (dynamicStart != null) start(route = { _ -> dynamicStart() }) else start(home)
                    screens(home, a, login)
                    modals(alert)
                    intercept(guard = { _ ->
                        guardGate?.await()
                        decision
                    }) {
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

    private fun TestScope.link(hash: String) = fakeBrowser(FakeBrowser.ORIGIN + "/" + hash)

    @Test
    fun `with a browser a static root opens on a blank placeholder and then its start`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val browser = fakeBrowser()
            val store = store(browser)

            val opening = store.selectState<NavigationState>().first()
            assertIs<LoadingModal>(opening.currentEntry.navigatable)
            assertTrue(opening.isBootstrapping)

            advanceUntilIdle()

            assertEquals(listOf("home"), store.locations())
            assertFalse(store.selectState<NavigationState>().first().isBootstrapping)
            assertEquals(listOf("#/home"), browser.urls)
        }

    @Test
    fun `without a browser a static root opens on its start as before`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val store = store(browser = null)

            val opening = store.selectState<NavigationState>().first()
            assertEquals("home", opening.currentEntry.route)
            assertEquals(null, opening.lastNavigationAction)
        }

    @Test
    fun `a cold link never shows the start screen on its own`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val browser = link("#/a")
            val store = store(browser)
            val seen = mutableListOf<String>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                store.selectState<NavigationState>().collect { seen += it.currentEntry.route }
            }

            advanceUntilIdle()

            assertEquals(listOf("home", "a"), store.locations())
            assertTrue("home" !in seen, "the start screen was current on its own: $seen")
            assertEquals(listOf("#/a"), browser.urls)
        }

    @Test
    fun `a dynamic root cold link lands with its start beneath it`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val browser = link("#/a")
            val store = store(browser, dynamicStart = { home })

            advanceUntilIdle()

            assertEquals(listOf("home", "a"), store.locations())
        }

    @Test
    fun `a cold link runs the root start before any guard is asked`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            var sessionReady = false
            val browser = link("#/admin/dashboard")
            val store = createStore {
                module(
                    createNavigationModule {
                        browserHistoryForTesting(browser, UrlStyle.Hash)
                        loadingModal(loading)
                        rootGraph {
                            start(route = { _ ->
                                sessionReady = true
                                home
                            })
                            screens(home, login)
                            intercept(guard = { _ ->
                                if (sessionReady) GuardResult.Allow else GuardResult.RedirectTo(login)
                            }) {
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

            advanceUntilIdle()

            assertEquals("dashboard", store.selectState<NavigationState>().first().currentEntry.route)
        }

    @Test
    fun `a cold link a guard rejects lands on the start and replaces the entry`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            decision = GuardResult.Reject
            val browser = link("#/admin/dashboard")
            val store = store(browser)

            advanceUntilIdle()

            assertEquals(listOf("home"), store.locations())
            assertEquals(listOf("#/home"), browser.urls)
        }

    @Test
    fun `a cold link a guard redirects lands on the redirect and replaces the entry`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            decision = GuardResult.RedirectTo(login)
            val browser = link("#/admin/dashboard")
            val store = store(browser)

            advanceUntilIdle()

            assertEquals("login", store.selectState<NavigationState>().first().currentEntry.route)
            assertEquals(listOf("#/login"), browser.urls)
        }

    @Test
    fun `a cold link to an unknown route lands on the start`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val browser = link("#/no/such/place")
            val store = store(browser)

            advanceUntilIdle()

            assertEquals(listOf("home"), store.locations())
            assertEquals(listOf("#/home"), browser.urls)
        }

    @Test
    fun `a cold link to an unknown route keeps the typed url on the not found screen`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val browser = link("#/no/such/place")
            val store = store(browser, withNotFound = true)

            advanceUntilIdle()

            assertEquals("missing", store.selectState<NavigationState>().first().currentEntry.route)
            assertEquals(listOf("#/no/such/place"), browser.urls)
            assertEquals(listOf("missing"), store.historyEntry(browser)?.snapshot?.entries?.map { it.path })
        }

    @Test
    fun `a start that fails with no crash screen stays on the placeholder and writes nothing`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val browser = fakeBrowser()
            val store = store(browser, dynamicStart = { error("configuration unavailable") })

            advanceUntilIdle()

            assertIs<LoadingModal>(store.selectState<NavigationState>().first().currentEntry.navigatable)
            assertTrue(browser.log.isEmpty())
        }

    @Test
    fun `a system alert raised while a cold link resolves stays on top`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val gate = CompletableDeferred<Unit>()
            guardGate = gate
            val browser = link("#/admin/dashboard")
            val store = store(browser)
            runCurrent()

            store.navigation { navigateTo(alert) }
            gate.complete(Unit)
            advanceUntilIdle()

            val state = store.selectState<NavigationState>().first()
            assertEquals("alert", state.currentEntry.route)
            assertEquals(listOf("home", "admin/dashboard"), state.backStack.filter { it.navigatable != alert }.map { it.location })
            assertEquals(listOf("#/admin/dashboard"), browser.urls)
        }

    @Test
    fun `a reload restores the stack when the dynamic start still agrees`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            var asked = 0
            val browser = fakeBrowser()
            val first = store(browser, dynamicStart = { home })
            advanceUntilIdle()
            first.navigation { navigateTo("a") }
            advanceUntilIdle()
            first.cleanup()
            browser.reload()

            val second = store(browser, dynamicStart = {
                asked++
                home
            })
            advanceUntilIdle()

            assertEquals(listOf("home", "a"), second.locations())
            assertTrue(asked > 0)
            assertIs<NavigationAction.Traverse>(second.selectState<NavigationState>().first().lastNavigationAction)
        }
}
