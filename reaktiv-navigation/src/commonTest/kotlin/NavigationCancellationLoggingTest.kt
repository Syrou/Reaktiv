import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import io.github.syrou.reaktiv.core.createStore
import io.github.syrou.reaktiv.core.util.ReaktivDebug
import io.github.syrou.reaktiv.core.util.ReaktivLogSink
import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.createNavigationModule
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.extension.navigation
import io.github.syrou.reaktiv.navigation.model.GuardResult
import io.github.syrou.reaktiv.navigation.param.Params
import io.github.syrou.reaktiv.navigation.transition.NavTransition
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.DurationUnit
import kotlin.time.toDuration

@OptIn(ExperimentalCoroutinesApi::class)
class NavigationCancellationLoggingTest {

    private val lines = mutableListOf<String>()
    private val sink = ReaktivLogSink { _, category, message -> lines.add("$category|$message") }

    @BeforeTest
    fun install() {
        lines.clear()
        ReaktivDebug.enable()
        ReaktivDebug.addSink(sink)
    }

    @AfterTest
    fun remove() {
        ReaktivDebug.removeSink(sink)
        ReaktivDebug.disable()
    }

    private fun screen(name: String) = object : Screen {
        override val route = name
        override val enterTransition = NavTransition.None
        override val exitTransition = NavTransition.None

        @Composable
        override fun Content(params: Params) {
            Text(name)
        }
    }

    private val home = screen("home")
    private val slow = screen("slow")
    private val abandoned = screen("abandoned")

    @Test
    fun a_navigation_cancelled_while_queued_is_logged_as_cancelled() =
        runTest(timeout = 10.toDuration(DurationUnit.SECONDS)) {
            val gate = CompletableDeferred<Unit>()
            val store = createStore {
                module(createNavigationModule {
                    rootGraph {
                        start(home)
                        screens(home, abandoned)
                        intercept(guard = { gate.await(); GuardResult.Allow }) {
                            graph("slow-graph") { start(slow); screens(slow) }
                        }
                    }
                })
                coroutineContext(StandardTestDispatcher(testScheduler))
            }
            advanceUntilIdle()
            lines.clear()

            val holder = launch { store.navigation { navigateTo("slow-graph/slow") } }
            advanceUntilIdle()

            val caller = launch { store.navigation { navigateTo("abandoned") } }
            advanceUntilIdle()
            caller.cancel()
            advanceUntilIdle()

            assertTrue(
                lines.any { it.contains("cancelled") },
                "an abandoned navigation must leave a record rather than vanishing: $lines"
            )

            gate.complete(Unit)
            holder.join()
            advanceUntilIdle()
        }

    @Test
    fun the_cancelled_navigation_is_not_applied() =
        runTest(timeout = 10.toDuration(DurationUnit.SECONDS)) {
            val gate = CompletableDeferred<Unit>()
            val store = createStore {
                module(createNavigationModule {
                    rootGraph {
                        start(home)
                        screens(home, abandoned)
                        intercept(guard = { gate.await(); GuardResult.Allow }) {
                            graph("slow-graph") { start(slow); screens(slow) }
                        }
                    }
                })
                coroutineContext(StandardTestDispatcher(testScheduler))
            }
            advanceUntilIdle()

            val holder = launch { store.navigation { navigateTo("slow-graph/slow") } }
            advanceUntilIdle()
            val caller = launch { store.navigation { navigateTo("abandoned") } }
            advanceUntilIdle()
            caller.cancel()
            advanceUntilIdle()

            gate.complete(Unit)
            holder.join()
            advanceUntilIdle()

            assertEquals(
                "slow",
                store.selectState<NavigationState>().first().currentEntry.route,
                "the abandoned navigation must not land after the one it was queued behind"
            )
        }

    @Test
    fun a_navigation_cancelled_after_it_committed_is_not_reported_as_cancelled() =
        runTest(timeout = 10.toDuration(DurationUnit.SECONDS)) {
            val gate = CompletableDeferred<Unit>()
            val store = createStore {
                module(createNavigationModule {
                    rootGraph {
                        start(home)
                        screens(home)
                        intercept(guard = { gate.await(); GuardResult.Allow }) {
                            graph("slow-graph") { start(slow); screens(slow) }
                        }
                    }
                })
                coroutineContext(StandardTestDispatcher(testScheduler))
            }
            advanceUntilIdle()
            lines.clear()

            val caller = launch { store.navigation { navigateTo("slow-graph/slow") } }
            advanceUntilIdle()
            caller.cancel()
            gate.complete(Unit)
            advanceUntilIdle()

            val landed = store.selectState<NavigationState>().first().currentEntry.route
            assertEquals("slow", landed, "the commit is protected, so this navigation did happen")
            assertTrue(
                lines.none { it.contains("cancelled") },
                "and a navigation that was applied must not be recorded as cancelled: $lines"
            )
        }
}
