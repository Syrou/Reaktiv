import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import io.github.syrou.reaktiv.core.createStore
import io.github.syrou.reaktiv.core.util.selectState
import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.createNavigationModule
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.extension.navigation
import io.github.syrou.reaktiv.navigation.model.GuardResult
import io.github.syrou.reaktiv.navigation.param.Params
import io.github.syrou.reaktiv.navigation.transition.NavTransition
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.DurationUnit
import kotlin.time.toDuration

@OptIn(ExperimentalCoroutinesApi::class)
class TransitionSettleOverlapTest {

    private fun timedScreen(route: String, durationMs: Int) = object : Screen {
        override val route = route
        override val enterTransition = NavTransition.Custom(durationMillis = durationMs)
        override val exitTransition = NavTransition.Custom(durationMillis = durationMs)

        @Composable
        override fun Content(params: Params) { Text(route) }
    }

    private fun instantScreen(route: String) = object : Screen {
        override val route = route
        override val enterTransition = NavTransition.None
        override val exitTransition = NavTransition.None

        @Composable
        override fun Content(params: Params) { Text(route) }
    }

    private val screenA = timedScreen("a", 300)
    private val screenB = timedScreen("b", 300)
    private val zoneScreen = instantScreen("zone-home")

    @Test
    fun `queued navigation evaluates its guard while the previous transition is settling`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val dispatcher = StandardTestDispatcher(testScheduler)
            var guardEvaluatedAt = -1L
            val module = createNavigationModule {
                rootGraph {
                    start(screenA)
                    screens(screenA, screenB)
                    intercept(
                        guard = { _ ->
                            guardEvaluatedAt = testScheduler.currentTime
                            GuardResult.Allow
                        }
                    ) {
                        graph("zone") {
                            start(zoneScreen)
                            screens(zoneScreen)
                        }
                    }
                }
            }
            val store = createStore {
                module(module)
                coroutineContext(dispatcher)
            }
            advanceUntilIdle()

            val first = launch { store.navigation { navigateTo("b") } }
            testScheduler.runCurrent()
            assertEquals("b", store.selectState<NavigationState>().first().currentEntry.route)

            val second = launch { store.navigation { navigateTo("zone") } }
            testScheduler.runCurrent()
            assertEquals(0L, guardEvaluatedAt)
            assertEquals("b", store.selectState<NavigationState>().first().currentEntry.route)

            testScheduler.advanceTimeBy(250)
            testScheduler.runCurrent()
            assertEquals("b", store.selectState<NavigationState>().first().currentEntry.route)

            advanceUntilIdle()
            assertEquals("zone-home", store.selectState<NavigationState>().first().currentEntry.route)
            assertTrue(first.isCompleted)
            assertTrue(second.isCompleted)
        }

    @Test
    fun `navigate suspends until its own transition has settled`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val module = createNavigationModule {
                rootGraph {
                    start(screenA)
                    screens(screenA, screenB)
                }
            }
            val store = createStore {
                module(module)
                coroutineContext(dispatcher)
            }
            advanceUntilIdle()

            val job = launch { store.navigation { navigateTo("b") } }
            testScheduler.runCurrent()
            assertEquals("b", store.selectState<NavigationState>().first().currentEntry.route)
            assertFalse(job.isCompleted)

            testScheduler.advanceTimeBy(150)
            testScheduler.runCurrent()
            assertFalse(job.isCompleted)

            advanceUntilIdle()
            assertTrue(job.isCompleted)
            assertTrue(testScheduler.currentTime >= 300)
        }
}
