import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import io.github.syrou.reaktiv.core.createStore
import io.github.syrou.reaktiv.navigation.NavigationAction
import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.createNavigationModule
import io.github.syrou.reaktiv.navigation.definition.Modal
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
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.DurationUnit
import kotlin.time.toDuration

@OptIn(ExperimentalCoroutinesApi::class)
class SingleOperationParityTest {

    private fun screen(route: String) = object : Screen {
        override val route = route
        override val enterTransition = NavTransition.None
        override val exitTransition = NavTransition.None

        @Composable
        override fun Content(params: Params) {
            Text(route)
        }
    }

    private fun modal(route: String) = object : Modal {
        override val route = route
        override val enterTransition = NavTransition.None
        override val exitTransition = NavTransition.None

        @Composable
        override fun Content(params: Params) {
            Text(route)
        }
    }

    private val homeScreen = screen("home")
    private val profileScreen = screen("profile")
    private val guardedScreen = screen("guarded")
    private val firstModal = modal("first")
    private val secondModal = modal("second")

    private fun TestScope.store(guardGate: CompletableDeferred<Unit> = CompletableDeferred(Unit)) = createStore {
        module(
            createNavigationModule {
                rootGraph {
                    start(homeScreen)
                    screens(homeScreen, profileScreen, SettingsScreen)
                    modals(firstModal, secondModal)
                    intercept(guard = {
                        guardGate.await()
                        GuardResult.Allow
                    }) {
                        screens(guardedScreen)
                    }
                }
            }
        )
        coroutineContext(StandardTestDispatcher(testScheduler))
    }

    private suspend fun io.github.syrou.reaktiv.core.Store.locations() =
        selectState<NavigationState>().first().backStack.map { it.location }

    @Test
    fun `a back on its own is refused while an evaluation is showing`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val store = store()
            store.navigation { navigateTo("profile") }
            advanceUntilIdle()
            store.dispatch(NavigationAction.SetEvaluating(true))
            advanceUntilIdle()

            store.navigation { navigateBack() }
            advanceUntilIdle()

            assertEquals(listOf("home", "profile"), store.locations())
        }

    @Test
    fun `a back on its own does not queue behind a navigation that is still evaluating its guard`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val gate = CompletableDeferred<Unit>()
            val store = store(gate)
            store.navigation { navigateTo("profile") }
            advanceUntilIdle()

            val pending = launch { store.navigation { navigateTo("guarded") } }
            advanceUntilIdle()
            try {
                store.navigation { navigateBack() }
                advanceUntilIdle()
                assertEquals(listOf("home"), store.locations())
            } finally {
                gate.complete(Unit)
                pending.join()
            }
        }

    @Test
    fun `a back with a stale expected top key changes nothing`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val store = store()
            store.navigation { navigateTo("profile") }
            advanceUntilIdle()

            store.navigation { navigateBack("not-the-top") }
            advanceUntilIdle()

            assertEquals(listOf("home", "profile"), store.locations())
        }

    @Test
    fun `navigateTo with a Params value merges it like the parameter block did`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val store = store()

            store.navigation {
                params(Params.of("b" to 2))
                navigateTo("profile", Params.of("a" to 1))
            }
            store.navigation { navigateTo<SettingsScreen>(Params.of("c" to 3)) }
            advanceUntilIdle()

            val backStack = store.selectState<NavigationState>().first().backStack
            val profileParams = backStack.first { it.location == "profile" }.params
            assertEquals(1, profileParams.getInt("a"))
            assertEquals(2, profileParams.getInt("b"))
            assertEquals(3, backStack.last().params.getInt("c"))
        }

    @Test
    fun `dismissModal on its own dismisses a covered modal and keeps the screen above it`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val store = store()
            store.navigation { navigateTo("first") }
            store.navigation { navigateTo("profile") }
            advanceUntilIdle()

            store.navigation { dismissModal() }
            advanceUntilIdle()

            assertEquals(listOf("home", "profile"), store.locations())
        }

    @Test
    fun `clearAllModals pops back to the last screen`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val store = store()
            store.navigation { navigateTo("first") }
            store.navigation { navigateTo("second") }
            advanceUntilIdle()

            store.navigation { clearAllModals() }
            advanceUntilIdle()

            assertEquals(listOf("home"), store.locations())
        }

    @Test
    fun `dismissModal with an entry that is not a modal does nothing`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val store = store()
            store.navigation { navigateTo("profile") }
            advanceUntilIdle()
            val profile = store.selectState<NavigationState>().first().currentEntry

            store.navigation { dismissModal(profile) }
            advanceUntilIdle()

            assertEquals(listOf("home", "profile"), store.locations())
        }

    @Test
    fun `a deep link runs through the navigation block`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val store = store()

            store.navigation { navigateDeepLink("profile?source=push") }
            advanceUntilIdle()

            val state = store.selectState<NavigationState>().first()
            assertEquals("profile", state.currentEntry.location)
            assertEquals("push", state.currentEntry.params.getString("source"))
        }

    @Test
    fun `a deep link cannot share its block with other operations`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val store = store()
            advanceUntilIdle()

            val failure = assertFailsWith<IllegalArgumentException> {
                store.navigation {
                    navigateDeepLink("profile")
                    navigateTo("home")
                }
            }

            assertTrue(failure.message!!.contains("only operation"))
        }
}
