import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import io.github.syrou.reaktiv.core.createStore
import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.createNavigationModule
import io.github.syrou.reaktiv.navigation.definition.BackstackLifecycle
import io.github.syrou.reaktiv.navigation.definition.RemovalReason
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.extension.navigateBack
import io.github.syrou.reaktiv.navigation.extension.navigation
import io.github.syrou.reaktiv.navigation.model.GuardResult
import io.github.syrou.reaktiv.navigation.param.Params
import io.github.syrou.reaktiv.navigation.transition.NavTransition
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.DurationUnit
import kotlin.time.toDuration

@OptIn(ExperimentalCoroutinesApi::class)
class ResetCancellationTest {

    private fun screen(
        screenRoute: String,
        enter: NavTransition = NavTransition.None,
        onLifecycle: (BackstackLifecycle) -> Unit = {}
    ) = object : Screen {
        override val route = screenRoute
        override val enterTransition = enter
        override val exitTransition = NavTransition.None

        @Composable
        override fun Content(params: Params) {
            Text(screenRoute)
        }

        override suspend fun onLifecycleCreated(lifecycle: BackstackLifecycle) {
            onLifecycle(lifecycle)
        }
    }

    private fun guardedStore(
        dispatcher: TestDispatcher,
        gate: CompletableDeferred<Unit>
    ) = createStore {
        val home = screen("home")
        val guarded = screen("guarded")
        module(createNavigationModule {
            rootGraph {
                start(home)
                screens(home)
                intercept(guard = { gate.await(); GuardResult.Allow }) {
                    graph("secure") {
                        start(guarded)
                        screens(guarded)
                    }
                }
            }
        })
        coroutineContext(dispatcher)
    }

    @Test
    fun `reset cancels an in-flight navigation and its commit never lands`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val gate = CompletableDeferred<Unit>()
            val store = guardedStore(dispatcher, gate)
            advanceUntilIdle()

            val events = mutableListOf<String>()
            launch {
                try {
                    store.navigation { navigateTo("secure") }
                    events.add("navigated")
                } catch (e: CancellationException) {
                    events.add("cancelled")
                }
            }
            advanceUntilIdle()
            assertEquals(emptyList(), events, "Navigation should be suspended inside the guard")

            assertTrue(store.reset())
            advanceUntilIdle()
            assertEquals(listOf("cancelled"), events)

            gate.complete(Unit)
            advanceUntilIdle()
            val state = store.selectState<NavigationState>().first()
            assertEquals("home", state.currentEntry.route)
            assertEquals(1, state.backStack.size)
        }

    @Test
    fun `cancelling the caller does not interrupt a navigation`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val gate = CompletableDeferred<Unit>()
            val store = guardedStore(dispatcher, gate)
            advanceUntilIdle()

            val caller = launch { store.navigation { navigateTo("secure") } }
            advanceUntilIdle()
            caller.cancel()
            advanceUntilIdle()

            gate.complete(Unit)
            advanceUntilIdle()
            assertEquals("guarded", store.selectState<NavigationState>().first().currentEntry.route)
        }

    @Test
    fun `a screen still leaving when the store resets runs its removal handler exactly once`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val reasons = mutableListOf<RemovalReason>()
            val home = screen("home")
            val detail = screen("detail", enter = NavTransition.SlideInRight) { lifecycle ->
                lifecycle.invokeOnRemoval { reason -> reasons.add(reason) }
            }
            val store = createStore {
                module(createNavigationModule {
                    rootGraph {
                        start(home)
                        screens(home, detail)
                    }
                })
                coroutineContext(dispatcher)
            }
            advanceUntilIdle()

            store.navigation { navigateTo("detail") }
            advanceUntilIdle()

            launch { store.navigateBack() }
            runCurrent()
            assertEquals("home", store.selectState<NavigationState>().first().currentEntry.route)
            assertEquals(emptyList(), reasons, "Removal is still pending on the exit transition")

            assertTrue(store.reset())
            advanceUntilIdle()
            assertEquals(listOf(RemovalReason.RESET), reasons)
        }
}
