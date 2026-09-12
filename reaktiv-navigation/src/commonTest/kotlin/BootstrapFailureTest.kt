import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import io.github.syrou.reaktiv.core.createStore
import io.github.syrou.reaktiv.core.util.selectState
import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.createNavigationModule
import io.github.syrou.reaktiv.navigation.definition.LoadingModal
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.extension.setAppInteractive
import io.github.syrou.reaktiv.navigation.param.Params
import io.github.syrou.reaktiv.navigation.transition.NavTransition
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
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

    @Test
    fun `bootstrap runs without waiting when the host never reports interactivity`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val store = createStore {
                module(createNavigationModule {
                    loadingModal(loadingModal())
                    rootGraph {
                        start(route = { _ -> homeScreen })
                        screens(homeScreen)
                    }
                })
                coroutineContext(dispatcher)
            }
            advanceUntilIdle()

            val state = store.selectState<NavigationState>().first()
            assertFalse(state.isBootstrapping, "a store with no interactivity reports must not be held back")
            assertEquals("home", state.currentEntry.route)
        }

    @Test
    fun `losing interactivity abandons an in-flight bootstrap and unlocking retries it`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val gate = CompletableDeferred<Screen>()
            var attempts = 0
            val store = createStore {
                module(createNavigationModule {
                    loadingModal(loadingModal())
                    rootGraph {
                        start(route = { _ ->
                            attempts++
                            gate.await()
                        })
                        screens(homeScreen)
                    }
                })
                coroutineContext(dispatcher)
            }
            advanceUntilIdle()

            val whileLoading = store.selectState<NavigationState>().first()
            assertTrue(whileLoading.isBootstrapping, "bootstrap is still waiting on the start lambda")
            assertEquals(1, attempts)

            store.setAppInteractive(false)
            advanceUntilIdle()

            val whileLocked = store.selectState<NavigationState>().first()
            assertTrue(whileLocked.isBootstrapping, "bootstrap must not resolve while the app is locked")
            assertEquals("loading", whileLocked.currentEntry.route)

            gate.complete(homeScreen)
            advanceUntilIdle()

            val stillLocked = store.selectState<NavigationState>().first()
            assertTrue(
                stillLocked.isBootstrapping,
                "the abandoned attempt must not resolve after the app has gone away"
            )

            store.setAppInteractive(true)
            advanceUntilIdle()

            val afterUnlock = store.selectState<NavigationState>().first()
            assertFalse(afterUnlock.isBootstrapping, "bootstrap must resolve once the app becomes interactive")
            assertEquals("home", afterUnlock.currentEntry.route)
            assertFalse(afterUnlock.backStack.any { it.route == "loading" })
            assertEquals(2, attempts, "the start lambda must run again on the unlocked attempt")
        }

    @Test
    fun `an eager failure is retried on the first interactivity report even without a preceding loss`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val gate = CompletableDeferred<Screen>()
            var attempts = 0
            val store = createStore {
                module(createNavigationModule {
                    loadingModal(loadingModal())
                    rootGraph {
                        start(route = { _ ->
                            attempts++
                            withTimeout(50) { gate.await() }
                        })
                        screens(homeScreen)
                    }
                })
                coroutineContext(dispatcher)
            }
            advanceUntilIdle()

            val afterEagerFailure = store.selectState<NavigationState>().first()
            assertTrue(afterEagerFailure.isBootstrapping, "the eager attempt failed while the app was away")
            assertEquals(1, attempts)

            gate.complete(homeScreen)
            store.setAppInteractive(true)
            advanceUntilIdle()

            val afterUnlock = store.selectState<NavigationState>().first()
            assertFalse(
                afterUnlock.isBootstrapping,
                "the first interactivity report must retry bootstrap even though it was never told the app went away"
            )
            assertEquals("home", afterUnlock.currentEntry.route)
            assertEquals(2, attempts)
        }

    @Test
    fun `a start lambda that times out is retried when the app next becomes interactive`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val gate = CompletableDeferred<Screen>()
            var attempts = 0
            val store = createStore {
                module(createNavigationModule {
                    loadingModal(loadingModal())
                    rootGraph {
                        start(route = { _ ->
                            attempts++
                            withTimeout(50) { gate.await() }
                        })
                        screens(homeScreen)
                    }
                })
                coroutineContext(dispatcher)
            }
            advanceUntilIdle()

            val afterFailure = store.selectState<NavigationState>().first()
            assertTrue(afterFailure.isBootstrapping, "a timed out start lambda leaves bootstrap armed")
            assertEquals(1, attempts)

            gate.complete(homeScreen)
            store.setAppInteractive(false)
            advanceUntilIdle()
            store.setAppInteractive(true)
            advanceUntilIdle()

            val afterRetry = store.selectState<NavigationState>().first()
            assertFalse(afterRetry.isBootstrapping, "becoming interactive again must retry bootstrap")
            assertEquals("home", afterRetry.currentEntry.route)
            assertEquals(2, attempts)
        }
}
