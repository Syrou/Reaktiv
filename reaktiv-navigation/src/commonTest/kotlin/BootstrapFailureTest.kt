import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import io.github.syrou.reaktiv.core.createStore
import io.github.syrou.reaktiv.navigation.NavigationLogic
import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.createNavigationModule
import io.github.syrou.reaktiv.navigation.definition.LoadingModal
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.extension.navigation
import io.github.syrou.reaktiv.navigation.model.StartFailure
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
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.DurationUnit
import kotlin.time.toDuration

@OptIn(ExperimentalCoroutinesApi::class)
class BootstrapFailureTest {

    private fun screen(route: String) = object : Screen {
        override val route = route
        override val enterTransition = NavTransition.None
        override val exitTransition = NavTransition.None

        @Composable
        override fun Content(params: Params) { Text(route) }
    }

    private fun loadingModal() = object : LoadingModal {
        override val route = "loading"
        override val enterTransition = NavTransition.None
        override val exitTransition = NavTransition.None

        @Composable
        override fun Content(params: Params) { Text("loading") }
    }

    private val homeScreen = screen("home")
    private val crashDestination = screen("crash")

    @Test
    fun `a start lambda that resolves completes bootstrap`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val store = createStore {
                module(createNavigationModule {
                    loadingModal(loadingModal())
                    rootGraph {
                        start(route = { _ -> homeScreen })
                        screens(homeScreen)
                    }
                })
                coroutineContext(StandardTestDispatcher(testScheduler))
            }
            advanceUntilIdle()

            val state = store.selectState<NavigationState>().first()
            assertFalse(state.isBootstrapping)
            assertEquals("home", state.currentEntry.route)
            assertFalse(state.backStack.any { it.route == "loading" })
        }

    @Test
    fun `a start lambda that throws with no crash screen stays on the loading modal and records the failure`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val store = createStore {
                module(createNavigationModule {
                    loadingModal(loadingModal())
                    rootGraph {
                        start(route = { _ -> throw IllegalStateException("no destination") })
                        screens(homeScreen)
                    }
                })
                coroutineContext(StandardTestDispatcher(testScheduler))
            }
            advanceUntilIdle()

            val state = store.selectState<NavigationState>().first()
            assertEquals("loading", state.currentEntry.route)
            assertFalse(state.isBootstrapping)
            assertEquals(StartFailure("IllegalStateException", "no destination"), state.startFailure)
        }

    @Test
    fun `navigation keeps working after a start that failed`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val store = createStore {
                module(createNavigationModule {
                    loadingModal(loadingModal())
                    rootGraph {
                        start(route = { _ -> throw IllegalStateException("no destination") })
                        screens(homeScreen)
                    }
                })
                coroutineContext(StandardTestDispatcher(testScheduler))
            }
            advanceUntilIdle()

            val navigation = launch { store.navigation { navigateTo("home") } }
            advanceUntilIdle()

            assertTrue(navigation.isCompleted)
            assertEquals(listOf("home"), store.selectState<NavigationState>().first().backStack.map { it.route })
        }

    @Test
    fun `retryStart runs the start again and lands once the cause is gone`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            var online = false
            val store = createStore {
                module(createNavigationModule {
                    loadingModal(loadingModal())
                    rootGraph {
                        start(route = { _ -> if (online) homeScreen else throw IllegalStateException("offline") })
                        screens(homeScreen)
                    }
                })
                coroutineContext(StandardTestDispatcher(testScheduler))
            }
            advanceUntilIdle()
            assertEquals("offline", store.selectState<NavigationState>().first().startFailure?.exceptionMessage)

            online = true
            store.selectLogic<NavigationLogic>().retryStart()
            advanceUntilIdle()

            val state = store.selectState<NavigationState>().first()
            assertEquals(listOf("home"), state.backStack.map { it.route })
            assertNull(state.startFailure)
        }

    @Test
    fun `retryStart records the failure again when the start still fails`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            var attempts = 0
            val store = createStore {
                module(createNavigationModule {
                    loadingModal(loadingModal())
                    rootGraph {
                        start(route = { _ ->
                            attempts++
                            throw IllegalStateException("attempt $attempts")
                        })
                        screens(homeScreen)
                    }
                })
                coroutineContext(StandardTestDispatcher(testScheduler))
            }
            advanceUntilIdle()

            store.selectLogic<NavigationLogic>().retryStart()
            advanceUntilIdle()

            val state = store.selectState<NavigationState>().first()
            assertEquals(2, attempts)
            assertEquals("attempt 2", state.startFailure?.exceptionMessage)
            assertEquals("loading", state.currentEntry.route)
        }

    @Test
    fun `retryStart does nothing when the start did not fail`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            var starts = 0
            val store = createStore {
                module(createNavigationModule {
                    loadingModal(loadingModal())
                    rootGraph {
                        start(route = { _ ->
                            starts++
                            homeScreen
                        })
                        screens(homeScreen)
                    }
                })
                coroutineContext(StandardTestDispatcher(testScheduler))
            }
            advanceUntilIdle()

            store.selectLogic<NavigationLogic>().retryStart()
            advanceUntilIdle()

            assertEquals(1, starts)
            assertEquals("home", store.selectState<NavigationState>().first().currentEntry.route)
        }

    @Test
    fun `a start lambda that throws completes bootstrap when a crash screen is configured`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val store = createStore {
                module(createNavigationModule {
                    loadingModal(loadingModal())
                    crashScreen(crashDestination)
                    rootGraph {
                        start(route = { _ -> throw IllegalStateException("no destination") })
                        screens(homeScreen)
                    }
                })
                coroutineContext(StandardTestDispatcher(testScheduler))
            }
            advanceUntilIdle()

            val state = store.selectState<NavigationState>().first()
            assertFalse(
                state.isBootstrapping,
                "a configured crash screen makes the failure terminal instead of leaving the store pinned"
            )
            assertEquals("no destination", state.startFailure?.exceptionMessage)
        }
}
