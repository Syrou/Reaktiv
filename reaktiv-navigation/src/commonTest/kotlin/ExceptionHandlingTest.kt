import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import io.github.syrou.reaktiv.core.createStore
import io.github.syrou.reaktiv.core.util.ReaktivDebug
import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.createNavigationModule
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.exception.RouteNotFoundException
import io.github.syrou.reaktiv.navigation.extension.navigation
import io.github.syrou.reaktiv.navigation.param.Params
import io.github.syrou.reaktiv.navigation.transition.NavTransition
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.DurationUnit
import kotlin.time.toDuration


@OptIn(ExperimentalCoroutinesApi::class)
class ExceptionHandlingTest {

    @BeforeTest
    fun before(){
        ReaktivDebug.enable()
    }

    private fun createScreen(route: String) = object : Screen {
        override val route = route
        override val enterTransition = NavTransition.None
        override val exitTransition = NavTransition.None
        override val requiresAuth = false

        @Composable
        override fun Content(params: Params) {
            Text(route)
        }
    }

    private val homeScreen = createScreen("home")
    private val profileScreen = createScreen("profile")

    @Test
    fun `test RouteNotFoundException for invalid simple routes`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val testDispatcher = StandardTestDispatcher(testScheduler)
            val navigationModule = createNavigationModule {
                rootGraph {
                    startScreen(homeScreen)
                    screens(homeScreen, profileScreen)
                }
            }

            val store = createStore {
                module(navigationModule)
                coroutineContext(testDispatcher)
            }

            val exception = assertFailsWith<RouteNotFoundException> {
                store.navigation { navigateTo("nonexistent") }
                advanceUntilIdle()
            }

            assertTrue(exception.message?.contains("nonexistent") ?: false)
        }

    @Test
    fun `test RouteNotFoundException for invalid nested routes`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val testDispatcher = StandardTestDispatcher(testScheduler)
            val navigationModule = createNavigationModule {
                rootGraph {
                    startScreen(homeScreen)
                    screens(homeScreen)

                    graph("content") {
                        startScreen(profileScreen)
                        screens(profileScreen)
                    }
                }
            }

            val store = createStore {
                module(navigationModule)
                coroutineContext(testDispatcher)
            }

            val exception = assertFailsWith<RouteNotFoundException> {
                store.navigation { navigateTo("content/nonexistent") }
                advanceUntilIdle()
            }

            assertTrue(exception.message?.contains("nonexistent") ?: false)
        }

    @Test
    fun `test RouteNotFoundException for broken graph reference`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val testDispatcher = StandardTestDispatcher(testScheduler)
            val navigationModule = createNavigationModule {
                rootGraph {
                    startScreen(homeScreen)
                    screens(homeScreen)

                    graph("broken") {
                        // This graph doesn't exist
                        startGraph("nonexistent")
                    }
                }
            }

            val store = createStore {
                module(navigationModule)
                coroutineContext(testDispatcher)
            }

            val exception = assertFailsWith<RouteNotFoundException> {
                store.navigation { navigateTo("broken") }
                advanceUntilIdle()
            }

            // Should mention that the route resolution failed
            assertTrue(exception.message?.contains("broken") ?: false)
        }

    @Test
    fun `test IllegalStateException for invalid navigation configuration`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val testDispatcher = StandardTestDispatcher(testScheduler)
            val navigationModule = createNavigationModule {
                rootGraph {
                    startScreen(homeScreen)
                    screens(homeScreen, profileScreen)
                }
            }

            val store = createStore {
                module(navigationModule)
                coroutineContext(testDispatcher)
            }

            // Test clearBackStack + popUpTo combination
            val exception1 = assertFailsWith<IllegalArgumentException> {
                store.navigation {
                    clearBackStack()
                    popUpTo("home")
                    navigateTo("profile")
                }
            }
            assertTrue(exception1.message?.contains("clearBackStack") ?: false)

            // Test clearBackStack + replaceCurrent combination
            val exception2 = assertFailsWith<IllegalArgumentException> {
                store.navigation {
                    navigateTo("profile")
                    clearBackStack()
                    navigateTo("home", replaceCurrent = true)
                }
            }
            assertTrue(exception2.message?.contains("replaceCurrent") ?: false)
        }

    @Test
    fun `test RouteNotFoundException for popUpTo with invalid route`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val testDispatcher = StandardTestDispatcher(testScheduler)
            val navigationModule = createNavigationModule {
                rootGraph {
                    startScreen(homeScreen)
                    screens(homeScreen, profileScreen)
                }
            }

            val store = createStore {
                module(navigationModule)
                coroutineContext(testDispatcher)
            }

            store.navigation { navigateTo("profile") }
            advanceUntilIdle()

            val exception = assertFailsWith<RouteNotFoundException> {
                store.navigation { popUpTo("nonexistent") }
                advanceUntilIdle()
            }
            assertTrue(exception.message?.contains("nonexistent") ?: false)
        }

    @Test
    fun `test state remains consistent after failed navigation`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val testDispatcher = StandardTestDispatcher(testScheduler)
            val navigationModule = createNavigationModule {
                rootGraph {
                    startScreen(homeScreen)
                    screens(homeScreen, profileScreen)
                }
            }

            val store = createStore {
                module(navigationModule)
                coroutineContext(testDispatcher)
            }

            // Navigate to a valid state first
            store.navigation { navigateTo("profile") }
            advanceUntilIdle()

            val stateBeforeFail = store.selectState<NavigationState>().first()

            // Try to navigate to invalid route
            assertFailsWith<RouteNotFoundException> {
                store.navigation { navigateTo("nonexistent") }
                advanceUntilIdle()
            }

            // State should remain unchanged after failed navigation
            val stateAfterFail = store.selectState<NavigationState>().first()
            assertEquals(stateBeforeFail.currentEntry, stateAfterFail.currentEntry)
            assertEquals(stateBeforeFail.backStack.size, stateAfterFail.backStack.size)
            assertEquals("profile", stateAfterFail.currentEntry.route)
        }

    @Test
    fun `test IllegalStateException for popUpTo empty backstack`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val testDispatcher = StandardTestDispatcher(testScheduler)
            val navigationModule = createNavigationModule {
                rootGraph {
                    startScreen(homeScreen)
                    screens(homeScreen, profileScreen)
                }
            }

            val store = createStore {
                module(navigationModule)
                coroutineContext(testDispatcher)
            }

            // Clear to single entry
            store.navigation { 
                clearBackStack()
                navigateTo("profile") 
            }
            advanceUntilIdle()

            val exception = assertFailsWith<IllegalStateException> {
                store.navigation { popUpTo("profile", inclusive = true) }
                advanceUntilIdle()
            }
            assertTrue(exception.message?.contains("empty back stack") ?: false)
        }
}