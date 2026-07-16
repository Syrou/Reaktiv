import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import io.github.syrou.reaktiv.core.createStore
import io.github.syrou.reaktiv.core.util.selectLogic
import io.github.syrou.reaktiv.core.util.selectState
import io.github.syrou.reaktiv.navigation.NavigationLogic
import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.NavigationOutcome
import io.github.syrou.reaktiv.navigation.createNavigationModule
import io.github.syrou.reaktiv.navigation.definition.Screen
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
import kotlin.test.assertIs
import kotlin.time.DurationUnit
import kotlin.time.toDuration

@OptIn(ExperimentalCoroutinesApi::class)
class NavigationOutcomeTest {

    private fun screen(route: String) = object : Screen {
        override val route = route
        override val enterTransition = NavTransition.None
        override val exitTransition = NavTransition.None

        @Composable
        override fun Content(params: Params) { Text(route) }
    }

    @Test
    fun `navigate returns Success on normal navigation`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val testDispatcher = StandardTestDispatcher(testScheduler)
            val homeScreen = screen("home")
            val profileScreen = screen("profile")
            val store = createStore {
                module(createNavigationModule {
                    rootGraph {
                        start(homeScreen)
                        screens(homeScreen, profileScreen)
                    }
                })
                coroutineContext(testDispatcher)
            }
            advanceUntilIdle()

            val logic = store.selectLogic<NavigationLogic>()
            val outcome = logic.navigate { navigateTo("profile") }
            advanceUntilIdle()

            assertEquals(NavigationOutcome.Success, outcome)
        }

    @Test
    fun `navigate queues behind an in-progress navigation instead of dropping`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val testDispatcher = StandardTestDispatcher(testScheduler)
            val homeScreen = screen("home")
            val protectedScreen = screen("protected")
            val guardBlocker = CompletableDeferred<Unit>()
            val store = createStore {
                module(createNavigationModule {
                    rootGraph {
                        start(homeScreen)
                        screens(homeScreen)
                        intercept(guard = { guardBlocker.await(); GuardResult.Allow }) {
                            graph("secure") {
                                start(protectedScreen)
                                screens(protectedScreen)
                            }
                        }
                    }
                })
                coroutineContext(testDispatcher)
            }
            advanceUntilIdle()

            val logic = store.selectLogic<NavigationLogic>()

            launch { logic.navigate { navigateTo("secure/protected") } }
            advanceUntilIdle()

            var secondOutcome: NavigationOutcome? = null
            launch { secondOutcome = logic.navigate { navigateTo("home") } }
            advanceUntilIdle()
            assertEquals(null, secondOutcome, "Second navigation should be queued while the first holds the lock")

            guardBlocker.complete(Unit)
            advanceUntilIdle()

            assertEquals(NavigationOutcome.Success, secondOutcome, "Queued navigation must execute once the lock frees")
            val finalState = store.selectState<NavigationState>().first()
            assertEquals("home", finalState.currentEntry.route, "The queued navigation should have landed last")
        }

    @Test
    fun `navigate returns Rejected when guard rejects`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val testDispatcher = StandardTestDispatcher(testScheduler)
            val homeScreen = screen("home")
            val protectedScreen = screen("protected")
            val store = createStore {
                module(createNavigationModule {
                    rootGraph {
                        start(homeScreen)
                        screens(homeScreen)
                        intercept(guard = { GuardResult.Reject }) {
                            graph("secure") {
                                start(protectedScreen)
                                screens(protectedScreen)
                            }
                        }
                    }
                })
                coroutineContext(testDispatcher)
            }
            advanceUntilIdle()

            val logic = store.selectLogic<NavigationLogic>()
            val outcome = logic.navigate { navigateTo("secure/protected") }
            advanceUntilIdle()

            assertEquals(NavigationOutcome.Rejected, outcome)
        }

    @Test
    fun `navigate returns Redirected when guard redirects`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val testDispatcher = StandardTestDispatcher(testScheduler)
            val homeScreen = screen("home")
            val loginScreen = screen("login")
            val protectedScreen = screen("protected")
            val store = createStore {
                module(createNavigationModule {
                    rootGraph {
                        start(homeScreen)
                        screens(homeScreen, loginScreen)
                        intercept(guard = { GuardResult.RedirectTo(loginScreen) }) {
                            graph("secure") {
                                start(protectedScreen)
                                screens(protectedScreen)
                            }
                        }
                    }
                })
                coroutineContext(testDispatcher)
            }
            advanceUntilIdle()

            val logic = store.selectLogic<NavigationLogic>()
            val outcome = logic.navigate { navigateTo("secure/protected") }
            advanceUntilIdle()

            assertIs<NavigationOutcome.Redirected>(outcome)
        }
}
