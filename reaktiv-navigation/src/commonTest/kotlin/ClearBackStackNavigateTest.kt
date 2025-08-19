import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import io.github.syrou.reaktiv.core.createStore
import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.createNavigationModule
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.extension.navigation
import io.github.syrou.reaktiv.navigation.transition.NavTransition
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.DurationUnit
import kotlin.time.toDuration

@OptIn(ExperimentalCoroutinesApi::class)
class ClearBackStackNavigateTest {

    private fun createScreen(route: String, title: String = route) = object : Screen {
        override val route = route
        override val enterTransition = NavTransition.None
        override val exitTransition = NavTransition.None
        override val requiresAuth = false

        @Composable
        override fun Content(params: Map<String, Any>) {
            Text(title)
        }
    }

    private val splashScreen = createScreen("splash", "Splash")
    // NewsScreen equivalent
    private val homeScreen = createScreen("overview", "Home")
    // NewsListScreen equivalent
    private val listScreen = createScreen("list", "List")

    private fun createTestNavigationModule() = createNavigationModule {
        rootGraph {
            startScreen(splashScreen)
            screens(splashScreen)

            graph("home") {
                startGraph("news")
                graph("news") {
                    startScreen(homeScreen)
                    screens(homeScreen, listScreen)
                }
            }
        }
    }

    @Test
    fun `test clearBackStack followed by navigateTo in same block`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val testDispatcher = StandardTestDispatcher(testScheduler)
            val store = createStore {
                module(createTestNavigationModule())
                coroutineContext(testDispatcher)
            }

            advanceUntilIdle()
            var state = store.selectState<NavigationState>().first()

            println("=== INITIAL STATE ===")
            println("Current: ${state.currentEntry.navigatable.route} (${state.currentEntry.graphId})")
            println("BackStack size: ${state.backStack.size}")
            println("BackStack: ${state.backStack.map { "${it.graphId}/${it.navigatable.route}" }}")
            println("Can go back: ${state.canGoBack}")

            // Navigate to establish some history first
            store.navigation {
                // This should resolve to home/news/overview
                navigateTo("home")
            }
            advanceUntilIdle()
            state = store.selectState<NavigationState>().first()

            println("\n=== AFTER NAVIGATE TO HOME ===")
            println("Current: ${state.currentEntry.navigatable.route} (${state.currentEntry.graphId})")
            println("BackStack size: ${state.backStack.size}")
            println("BackStack: ${state.backStack.map { "${it.graphId}/${it.navigatable.route}" }}")
            println("Can go back: ${state.canGoBack}")

            // Now test the problematic sequence: clearBackStack + navigateTo in same block
            store.navigation {
                clearBackStack()
                // Should result in backStack with 1 entry
                navigateTo("home")
            }
            advanceUntilIdle()
            state = store.selectState<NavigationState>().first()

            println("\n=== AFTER CLEAR + NAVIGATE (PROBLEMATIC) ===")
            println("Current: ${state.currentEntry.navigatable.route} (${state.currentEntry.graphId})")
            println("BackStack size: ${state.backStack.size}")
            println("BackStack: ${state.backStack.map { "${it.graphId}/${it.navigatable.route}" }}")
            println("Can go back: ${state.canGoBack}")

            // This should have 1 entry in backStack but we suspect it's empty
            assertEquals(1, state.backStack.size, "BackStack should have 1 entry after clearBackStack + navigateTo")
            assertEquals("overview", state.currentEntry.navigatable.route)
            assertEquals("news", state.currentEntry.graphId)

            // Now navigate to list screen - this should ADD to backStack
            store.navigation {
                navigateTo("home/news/list")
            }
            advanceUntilIdle()
            state = store.selectState<NavigationState>().first()

            println("\n=== AFTER NAVIGATE TO LIST ===")
            println("Current: ${state.currentEntry.navigatable.route} (${state.currentEntry.graphId})")
            println("BackStack size: ${state.backStack.size}")
            println("BackStack: ${state.backStack.map { "${it.graphId}/${it.navigatable.route}" }}")
            println("Can go back: ${state.canGoBack}")

            // This should have 2 entries and be able to go back
            assertEquals(2, state.backStack.size, "BackStack should have 2 entries after navigating to list")
            assertEquals("list", state.currentEntry.navigatable.route)
            assertEquals("news", state.currentEntry.graphId)
            assertTrue(state.canGoBack, "Should be able to go back with 2 entries in backStack")
        }

    @Test
    fun `test clearBackStack and navigateTo in separate blocks`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val testDispatcher = StandardTestDispatcher(testScheduler)
            val store = createStore {
                module(createTestNavigationModule())
                coroutineContext(testDispatcher)
            }

            advanceUntilIdle()

            // Navigate to establish history
            store.navigation { navigateTo("home") }
            advanceUntilIdle()

            // Test the workaround: separate blocks
            store.navigation { clearBackStack() }
            advanceUntilIdle()

            var state = store.selectState<NavigationState>().first()
            println("\n=== AFTER CLEAR ONLY ===")
            println("Current: ${state.currentEntry.navigatable.route} (${state.currentEntry.graphId})")
            println("BackStack size: ${state.backStack.size}")
            println("BackStack: ${state.backStack.map { "${it.graphId}/${it.navigatable.route}" }}")

            store.navigation { navigateTo("home") }
            advanceUntilIdle()
            state = store.selectState<NavigationState>().first()

            println("\n=== AFTER SEPARATE NAVIGATE ===")
            println("Current: ${state.currentEntry.navigatable.route} (${state.currentEntry.graphId})")
            println("BackStack size: ${state.backStack.size}")
            println("BackStack: ${state.backStack.map { "${it.graphId}/${it.navigatable.route}" }}")
            println("Can go back: ${state.canGoBack}")

            // This should work correctly
            assertEquals(1, state.backStack.size, "BackStack should have 1 entry after separate operations")

            // Navigate to list
            store.navigation { navigateTo("home/news/list") }
            advanceUntilIdle()
            state = store.selectState<NavigationState>().first()

            assertEquals(2, state.backStack.size, "Should have 2 entries after navigating to list")
            assertTrue(state.canGoBack, "Should be able to go back")
        }
}