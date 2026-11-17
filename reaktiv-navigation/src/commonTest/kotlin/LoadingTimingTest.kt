import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import io.github.syrou.reaktiv.core.Store
import io.github.syrou.reaktiv.core.createStore
import io.github.syrou.reaktiv.navigation.NavigationModule
import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.createNavigationModule
import io.github.syrou.reaktiv.navigation.definition.LoadingModal
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.extension.navigation
import io.github.syrou.reaktiv.navigation.model.GuardResult
import io.github.syrou.reaktiv.navigation.model.NavigationGuard
import io.github.syrou.reaktiv.navigation.param.Params
import io.github.syrou.reaktiv.navigation.transition.NavTransition
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.DurationUnit
import kotlin.time.toDuration

@OptIn(ExperimentalCoroutinesApi::class)
class LoadingTimingTest {

    private fun loading(minimum: Duration) = object : LoadingModal {
        override val route = "loading"
        override val enterTransition = NavTransition.None
        override val exitTransition = NavTransition.None
        override val minimumDisplay: Duration = minimum

        @Composable
        override fun Content(params: Params) {
            Text("loading")
        }
    }

    private fun checkedScreen(route: String, threshold: Duration? = null, guard: NavigationGuard): Screen =
        object : Screen {
            override val route = route
            override val enterTransition = NavTransition.None
            override val exitTransition = NavTransition.None
            override val guard: NavigationGuard = guard
            override val guardLoadingThreshold: Duration = threshold ?: super.guardLoadingThreshold

            @Composable
            override fun Content(params: Params) {
                Text(route)
            }
        }

    private val home = historyScreen("home")
    private val other = historyScreen("other")
    private val gate = CompletableDeferred<GuardResult>()

    private fun module(minimum: Duration = Duration.ZERO, startDelay: Duration = Duration.ZERO): NavigationModule =
        createNavigationModule {
            loadingModal(loading(minimum))
            rootGraph {
                start(route = { _ ->
                    delay(startDelay)
                    home
                }, loadingThreshold = 200.milliseconds)
                screens(
                    home,
                    other,
                    checkedScreen("quick") { _ ->
                        delay(300)
                        GuardResult.Allow
                    },
                    checkedScreen("patient", threshold = 500.milliseconds) { _ ->
                        delay(300)
                        GuardResult.Allow
                    },
                    checkedScreen("gated") { _ -> gate.await() },
                )
            }
        }

    private fun TestScope.store(navModule: NavigationModule) = createStore {
        module(navModule)
        coroutineContext(StandardTestDispatcher(testScheduler))
    }

    private suspend fun Store.state() = selectState<NavigationState>().first()

    @Test
    fun `a screen guard waits for its own loading threshold`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val store = store(module())
            advanceUntilIdle()

            launch { store.navigation { navigateTo("quick") } }
            advanceTimeBy(250)
            runCurrent()
            assertTrue(store.state().isEvaluatingNavigation, "the default threshold of 200 ms has passed")
            advanceUntilIdle()

            launch { store.navigation { navigateTo("patient") } }
            advanceTimeBy(250)
            runCurrent()
            assertFalse(store.state().isEvaluatingNavigation, "this guard waits 500 ms before showing loading")
            advanceUntilIdle()
            assertEquals("patient", store.state().currentEntry.route)
            assertFalse(store.state().isEvaluatingNavigation)
        }

    @Test
    fun `the loading modal stays up for its minimum display`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val store = store(module(minimum = 500.milliseconds))
            advanceUntilIdle()

            launch { store.navigation { navigateTo("quick") } }
            advanceTimeBy(250)
            runCurrent()
            assertTrue(store.state().isEvaluatingNavigation)

            advanceTimeBy(200)
            runCurrent()
            assertEquals("quick", store.state().currentEntry.route)
            assertTrue(store.state().isEvaluatingNavigation, "shown at 200 ms, so it stays until 700 ms")

            advanceTimeBy(300)
            runCurrent()
            assertFalse(store.state().isEvaluatingNavigation)
        }

    @Test
    fun `startup is never held for the minimum display`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val store = store(module(minimum = 500.milliseconds, startDelay = 300.milliseconds))

            advanceTimeBy(350)
            runCurrent()

            val state = store.state()
            assertFalse(state.isBootstrapping)
            assertEquals("home", state.currentEntry.route)
            assertFalse(state.isEvaluatingNavigation)
        }

    @Test
    fun `a cancelled navigation drops the loading modal at once`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val store = store(module(minimum = 500.milliseconds))
            advanceUntilIdle()

            launch { store.navigation { navigateTo("gated") } }
            advanceTimeBy(250)
            runCurrent()
            assertTrue(store.state().isEvaluatingNavigation)

            launch { store.navigation { navigateTo("other") } }
            advanceTimeBy(10)
            runCurrent()

            assertEquals("other", store.state().currentEntry.route)
            assertFalse(store.state().isEvaluatingNavigation)
        }
}
