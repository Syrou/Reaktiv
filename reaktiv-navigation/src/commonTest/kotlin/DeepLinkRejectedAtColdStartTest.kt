import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import io.github.syrou.reaktiv.core.createStore
import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.createNavigationModule
import io.github.syrou.reaktiv.navigation.definition.LoadingModal
import io.github.syrou.reaktiv.navigation.definition.NavigationPath
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.DurationUnit
import kotlin.time.toDuration

@OptIn(ExperimentalCoroutinesApi::class)
class DeepLinkRejectedAtColdStartTest {

    private fun screen(route: String) = object : Screen {
        override val route = route
        override val enterTransition = NavTransition.None
        override val exitTransition = NavTransition.None

        @Composable
        override fun Content(params: Params) {
            Text(route)
        }
    }

    private val loading = object : LoadingModal {
        override val route = "loading"
        override val enterTransition = NavTransition.None
        override val exitTransition = NavTransition.None

        @Composable
        override fun Content(params: Params) {
            Text("loading")
        }
    }

    private val homeScreen = screen("home")
    private val adminScreen = screen("admin")

    @Test
    fun `a cold start link that a guard rejects lands on the start destination`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val startGate = CompletableDeferred<Unit>()
            val store = createStore {
                module(
                    createNavigationModule {
                        loadingModal(loading)
                        rootGraph {
                            start(route = { _ ->
                                startGate.await()
                                NavigationPath("home")
                            })
                            screens(homeScreen)
                            intercept(guard = { GuardResult.Reject }) {
                                screens(adminScreen)
                            }
                        }
                    }
                )
                coroutineContext(StandardTestDispatcher(testScheduler))
            }
            advanceUntilIdle()
            assertTrue(store.selectState<NavigationState>().first().isBootstrapping)

            launch { store.navigation { navigateDeepLink("admin") } }
            advanceUntilIdle()
            startGate.complete(Unit)
            advanceUntilIdle()

            val state = store.selectState<NavigationState>().first()
            assertEquals("home", state.currentEntry.route)
            assertFalse(state.isBootstrapping)
        }
}
