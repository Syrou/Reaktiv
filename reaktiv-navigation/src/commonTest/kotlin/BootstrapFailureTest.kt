import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import io.github.syrou.reaktiv.core.createStore
import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.createNavigationModule
import io.github.syrou.reaktiv.navigation.definition.LoadingModal
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.param.Params
import io.github.syrou.reaktiv.navigation.transition.NavTransition
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
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
    fun `a start lambda that throws with no crash screen leaves navigation on the loading modal`() =
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
            assertTrue(
                state.isBootstrapping,
                "there is no correct destination to invent, so bootstrap stays unresolved"
            )
            assertEquals("loading", state.currentEntry.route)
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
        }
}
