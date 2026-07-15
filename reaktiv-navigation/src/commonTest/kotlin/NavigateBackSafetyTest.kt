import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import io.github.syrou.reaktiv.core.createStore
import io.github.syrou.reaktiv.navigation.NavigationAction
import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.createNavigationModule
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.extension.navigateBack
import io.github.syrou.reaktiv.navigation.extension.navigation
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
class NavigateBackSafetyTest {
    private fun createScreen(route: String, title: String = route) = object : Screen {
        override val route = route
        override val enterTransition = NavTransition.None
        override val exitTransition = NavTransition.None

        @Composable
        override fun Content(params: Params) {
            Text(title)
        }
    }

    private val homeScreen = createScreen("home", "Home")
    private val profileScreen = createScreen("profile", "Profile")
    private val settingsScreen = createScreen("settings", "Settings")

    private fun createSimpleNavigationModule() = createNavigationModule {
        rootGraph {
            startScreen(homeScreen)
            screens(homeScreen, profileScreen, settingsScreen)
        }
    }

    @Test
    fun `navigateBack is a no-op while isEvaluatingNavigation is true`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val testDispatcher = StandardTestDispatcher(testScheduler)
            val store = createStore {
                module(createSimpleNavigationModule())
                coroutineContext(testDispatcher)
            }
            store.navigation { navigateTo("profile") }
            advanceUntilIdle()

            store.dispatch(NavigationAction.SetEvaluating(true))
            advanceUntilIdle()

            store.navigateBack()
            advanceUntilIdle()

            val state = store.selectState<NavigationState>().first()
            assertTrue(state.isEvaluatingNavigation)
            assertEquals(2, state.backStack.size)
            assertEquals("profile", state.currentEntry.route)
        }

    @Test
    fun `navigateBack works again after evaluation completes`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val testDispatcher = StandardTestDispatcher(testScheduler)
            val store = createStore {
                module(createSimpleNavigationModule())
                coroutineContext(testDispatcher)
            }
            store.navigation { navigateTo("profile") }
            advanceUntilIdle()

            store.dispatch(NavigationAction.SetEvaluating(true))
            advanceUntilIdle()
            store.navigateBack()
            advanceUntilIdle()
            store.dispatch(NavigationAction.SetEvaluating(false))
            advanceUntilIdle()

            store.navigateBack()
            advanceUntilIdle()

            val state = store.selectState<NavigationState>().first()
            assertFalse(state.isEvaluatingNavigation)
            assertEquals(1, state.backStack.size)
            assertEquals("home", state.currentEntry.route)
        }

    @Test
    fun `navigateBack is a no-op at the root of the backstack`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val testDispatcher = StandardTestDispatcher(testScheduler)
            val store = createStore {
                module(createSimpleNavigationModule())
                coroutineContext(testDispatcher)
            }
            advanceUntilIdle()

            store.navigateBack()
            advanceUntilIdle()

            val state = store.selectState<NavigationState>().first()
            assertEquals(1, state.backStack.size)
            assertEquals("home", state.currentEntry.route)
        }

    @Test
    fun `navigateBack pops normally when not evaluating`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val testDispatcher = StandardTestDispatcher(testScheduler)
            val store = createStore {
                module(createSimpleNavigationModule())
                coroutineContext(testDispatcher)
            }
            store.navigation { navigateTo("profile") }
            advanceUntilIdle()
            store.navigation { navigateTo("settings") }
            advanceUntilIdle()

            store.navigateBack()
            advanceUntilIdle()

            val state = store.selectState<NavigationState>().first()
            assertEquals(2, state.backStack.size)
            assertEquals("profile", state.currentEntry.route)
        }
}
