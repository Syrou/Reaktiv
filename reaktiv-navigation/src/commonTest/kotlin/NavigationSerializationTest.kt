import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import io.github.syrou.reaktiv.core.createStore
import io.github.syrou.reaktiv.core.util.selectState
import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.createNavigationModule
import io.github.syrou.reaktiv.navigation.definition.LoadingModal
import io.github.syrou.reaktiv.navigation.definition.Modal
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.extension.dismissModal
import io.github.syrou.reaktiv.navigation.extension.navigate
import io.github.syrou.reaktiv.navigation.extension.navigation
import io.github.syrou.reaktiv.navigation.model.GuardResult
import io.github.syrou.reaktiv.navigation.param.Params
import io.github.syrou.reaktiv.navigation.transition.NavTransition
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.DurationUnit
import kotlin.time.toDuration

@OptIn(ExperimentalCoroutinesApi::class)
class NavigationSerializationTest {

    private fun screen(name: String) = object : Screen {
        override val route = name
        override val enterTransition = NavTransition.None
        override val exitTransition = NavTransition.None

        @Composable
        override fun Content(params: Params) { Text(name) }
    }

    private val sheet = object : Modal {
        override val route = "sheet"
        override val enterTransition = NavTransition.None
        override val exitTransition = NavTransition.None

        @Composable
        override fun Content(params: Params) { Text("sheet") }
    }

    private val overlay = object : LoadingModal {
        override val route = "loading"
        override val enterTransition = NavTransition.None
        override val exitTransition = NavTransition.None

        @Composable
        override fun Content(params: Params) { Text("loading") }
    }

    @Test
    fun `a modal dismissed while another navigation is evaluating is still dismissed`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val home = screen("home")
            val upsell = screen("upsell")
            val gate = CompletableDeferred<Unit>()
            val store = createStore {
                module(createNavigationModule {
                    loadingModal(overlay)
                    rootGraph {
                        start(home)
                        screens(home)
                        modals(sheet)
                        intercept(guard = {
                            gate.await()
                            GuardResult.Allow
                        }) {
                            screens(upsell)
                        }
                    }
                })
                coroutineContext(StandardTestDispatcher(testScheduler))
            }
            advanceUntilIdle()
            store.navigation { navigateTo("sheet") }
            advanceUntilIdle()

            val navigating = launch { store.navigate("upsell") }
            var evaluating = false
            try {
                advanceTimeBy(5_000)
                runCurrent()
                evaluating = store.selectState<NavigationState>().first().isEvaluatingNavigation
                launch { store.dismissModal() }
                runCurrent()
            } finally {
                gate.complete(Unit)
            }
            advanceUntilIdle()
            navigating.join()

            assertTrue(evaluating, "the navigation to upsell was not evaluating when the dismiss arrived")
            val state = store.selectState<NavigationState>().first()
            assertEquals("upsell", state.currentEntry.route)
            assertTrue(state.backStack.none { it.navigatable is Modal }, "left behind: ${state.backStack.map { it.route }}")
        }
}
