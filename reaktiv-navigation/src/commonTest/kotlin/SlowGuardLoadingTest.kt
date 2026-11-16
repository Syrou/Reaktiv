import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import io.github.syrou.reaktiv.core.Store
import io.github.syrou.reaktiv.core.createStore
import io.github.syrou.reaktiv.core.util.selectLogic
import io.github.syrou.reaktiv.navigation.NavigationLogic
import io.github.syrou.reaktiv.navigation.NavigationModule
import io.github.syrou.reaktiv.navigation.NavigationOutcome
import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.createNavigationModule
import io.github.syrou.reaktiv.navigation.definition.DismissSource
import io.github.syrou.reaktiv.navigation.definition.LoadingModal
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.extension.navigation
import io.github.syrou.reaktiv.navigation.extension.openLink
import io.github.syrou.reaktiv.navigation.link.LinkOutcome
import io.github.syrou.reaktiv.navigation.model.GuardResult
import io.github.syrou.reaktiv.navigation.model.NavigationGuard
import io.github.syrou.reaktiv.navigation.param.Params
import io.github.syrou.reaktiv.navigation.transition.NavTransition
import io.github.syrou.reaktiv.navigation.util.performUserBack
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.DurationUnit
import kotlin.time.toDuration

@OptIn(ExperimentalCoroutinesApi::class)
class SlowGuardLoadingTest {

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
    private val tabA = historyScreen("tab-a")
    private val tabB = historyScreen("tab-b")
    private val slowTab = historyScreen("slow-tab")
    private val slowOutside = historyScreen("slow-outside")

    private val gate = CompletableDeferred<GuardResult>()
    private var guardCancelled = false
    private var quickGuardMs = 0L

    private val slowGuard: suspend () -> GuardResult = {
        try {
            if (quickGuardMs > 0) {
                delay(quickGuardMs)
                GuardResult.Allow
            } else {
                gate.await()
            }
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            guardCancelled = true
            throw cancellation
        }
    }

    private val slowScreen = object : Screen {
        override val route = "slow-screen"
        override val enterTransition = NavTransition.None
        override val exitTransition = NavTransition.None
        override val guard: NavigationGuard = { _ -> slowGuard() }

        @Composable
        override fun Content(params: Params) {
            Text(route)
        }
    }

    private fun module(): NavigationModule = createNavigationModule {
        loadingModal(loading)
        rootGraph {
            start(home)
            screens(home)
            graph("tabs") {
                start(tabA)
                layout { content -> content() }
                screens(tabA, tabB, slowScreen)
                intercept(guard = { _ -> slowGuard() }) {
                    screens(slowTab)
                }
            }
            intercept(guard = { _ -> slowGuard() }) {
                screens(slowOutside)
            }
        }
    }

    private fun TestScope.store(navModule: NavigationModule = module()) = createStore {
        module(navModule)
        coroutineContext(StandardTestDispatcher(testScheduler))
    }

    private suspend fun Store.state() = selectState<NavigationState>().first()

    private suspend fun TestScope.onTabA(store: Store) {
        advanceUntilIdle()
        store.navigation { navigateTo("tabs/tab-a") }
        advanceUntilIdle()
        assertEquals("tab-a", store.state().currentEntry.route)
    }

    @Test
    fun `a slow guard inside a layout shows the loading modal within that layout`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val store = store()
            onTabA(store)

            val outcome = async { store.selectLogic<NavigationLogic>().navigate { navigateTo("tabs/slow-tab") } }
            advanceTimeBy(300)
            runCurrent()

            val loadingState = store.state()
            assertTrue(loadingState.isEvaluatingNavigation)
            assertEquals("tabs", loadingState.evaluatingInGraph)

            gate.complete(GuardResult.Allow)
            advanceUntilIdle()

            assertEquals(NavigationOutcome.Success, outcome.await())
            val landed = store.state()
            assertEquals("slow-tab", landed.currentEntry.route)
            assertFalse(landed.isEvaluatingNavigation)
            assertNull(landed.evaluatingInGraph)
        }

    @Test
    fun `a slow guard toward another part of the app covers the whole app`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val store = store()
            onTabA(store)

            async { store.selectLogic<NavigationLogic>().navigate { navigateTo("slow-outside") } }
            advanceTimeBy(300)
            runCurrent()

            val loadingState = store.state()
            assertTrue(loadingState.isEvaluatingNavigation)
            assertNull(loadingState.evaluatingInGraph)
            gate.complete(GuardResult.Allow)
            advanceUntilIdle()
        }

    @Test
    fun `a new navigation cancels one waiting behind its loading modal`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val store = store()
            onTabA(store)

            val slow = async { store.selectLogic<NavigationLogic>().navigate { navigateTo("tabs/slow-tab") } }
            advanceTimeBy(300)
            runCurrent()
            assertTrue(store.state().isEvaluatingNavigation)

            store.navigation { navigateTo("tabs/tab-b") }
            advanceUntilIdle()

            assertEquals(NavigationOutcome.Dropped, slow.await())
            assertTrue(guardCancelled)
            val state = store.state()
            assertEquals("tab-b", state.currentEntry.route)
            assertFalse(state.isEvaluatingNavigation)
            assertFalse("slow-tab" in state.backStack.map { it.route })
        }

    @Test
    fun `back cancels a navigation waiting behind its loading modal and stays`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val navModule = module()
            val store = store(navModule)
            onTabA(store)

            val slow = async { store.selectLogic<NavigationLogic>().navigate { navigateTo("tabs/slow-tab") } }
            advanceTimeBy(300)
            runCurrent()

            performUserBack(store, navModule, DismissSource.Back)
            advanceUntilIdle()

            assertEquals(NavigationOutcome.Dropped, slow.await())
            val state = store.state()
            assertEquals("tab-a", state.currentEntry.route)
            assertFalse(state.isEvaluatingNavigation)
        }

    @Test
    fun `a navigation behind a guard that answers within the threshold is not cancelled`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            quickGuardMs = 100
            val store = store()
            onTabA(store)

            val quick = async { store.selectLogic<NavigationLogic>().navigate { navigateTo("tabs/slow-tab") } }
            advanceTimeBy(50)
            runCurrent()
            val next = async { store.selectLogic<NavigationLogic>().navigate { navigateTo("tabs/tab-b") } }
            advanceUntilIdle()

            assertEquals(NavigationOutcome.Success, quick.await())
            assertEquals(NavigationOutcome.Success, next.await())
            assertFalse(guardCancelled)
            assertEquals(listOf("tab-a", "slow-tab", "tab-b"), store.state().backStack.map { it.route }.takeLast(3))
        }

    @Test
    fun `a slow screen guard shows the loading modal within the shared layout and a new navigation cancels it`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val store = store()
            onTabA(store)

            val slow = async { store.selectLogic<NavigationLogic>().navigate { navigateTo("tabs/slow-screen") } }
            advanceTimeBy(300)
            runCurrent()
            val loadingState = store.state()
            assertTrue(loadingState.isEvaluatingNavigation)
            assertEquals("tabs", loadingState.evaluatingInGraph)

            store.navigation { navigateTo("tabs/tab-b") }
            advanceUntilIdle()

            assertEquals(NavigationOutcome.Dropped, slow.await())
            assertTrue(guardCancelled)
            assertEquals("tab-b", store.state().currentEntry.route)
            assertFalse(store.state().isEvaluatingNavigation)
        }

    @Test
    fun `back cancels a slow screen guard and stays`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val navModule = module()
            val store = store(navModule)
            onTabA(store)

            val slow = async { store.selectLogic<NavigationLogic>().navigate { navigateTo("tabs/slow-screen") } }
            advanceTimeBy(300)
            runCurrent()

            performUserBack(store, navModule, DismissSource.Back)
            advanceUntilIdle()

            assertEquals(NavigationOutcome.Dropped, slow.await())
            assertEquals("tab-a", store.state().currentEntry.route)
            assertFalse(store.state().isEvaluatingNavigation)
        }

    @Test
    fun `a link overtaken while it waits behind its loading modal is reported as stale`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val store = store()
            onTabA(store)

            val link = async { store.openLink("tabs/slow-tab") }
            advanceTimeBy(300)
            runCurrent()
            store.navigation { navigateTo("tabs/tab-b") }
            advanceUntilIdle()

            assertEquals(LinkOutcome.Ignored("A newer navigation took over before the link landed"), link.await())
            assertEquals("tab-b", store.state().currentEntry.route)
        }
}
