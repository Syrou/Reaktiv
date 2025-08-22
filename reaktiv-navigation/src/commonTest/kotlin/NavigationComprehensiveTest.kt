import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import io.github.syrou.reaktiv.core.createStore
import io.github.syrou.reaktiv.core.util.ReaktivDebug
import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.createNavigationModule
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.exception.RouteNotFoundException
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
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.DurationUnit
import kotlin.time.toDuration

@OptIn(ExperimentalCoroutinesApi::class)
class NavigationComprehensiveTest {

    // Test Screens
    private val splashScreen = createTestScreen("splash", "Splash Screen")
    private val homeScreen = createTestScreen("home-main", "Home Main Screen")
    private val newsScreen = createTestScreen("overview", "News Overview")
    private val newsListScreen = createTestScreen("list", "News List")
    private val workspaceScreen = createTestScreen("workspace", "Workspace Screen")
    private val projectOverviewScreen = createTestScreen("overview", "Project Overview")
    private val projectTasksScreen = createTestScreen("tasks", "Project Tasks")
    private val projectSettingsScreen = createTestScreen("settings", "Project Settings")
    private val leaderboardScreen = createTestScreen("leaderboard", "Leaderboard Screen")
    private val settingsScreen = createTestScreen("settings", "Settings Screen")

    private fun createTestScreen(route: String, title: String) = object : Screen {
        override val route = route
        override val enterTransition = NavTransition.None
        override val exitTransition = NavTransition.None
        override val requiresAuth = false

        @Composable
        override fun Content(params: Params) {
            Text(title)
        }
    }

    // Create the navigation module that matches the user's structure
    private fun createTestNavigationModule() = createNavigationModule {
        rootGraph {
            startScreen(splashScreen)
            screens(splashScreen, settingsScreen)

            graph("home") {
                startGraph("news")  // Key: home references news

                graph("news") {
                    startScreen(newsScreen)
                    screens(newsScreen, newsListScreen)
                }

                graph("workspace") {
                    startScreen(workspaceScreen)

                    graph("projects") {
                        startScreen(projectOverviewScreen)
                        screens(projectOverviewScreen, projectTasksScreen, projectSettingsScreen)
                    }
                }

                graph("leaderboard") {
                    startScreen(leaderboardScreen)
                }
            }
        }
    }

    @Test
    fun `test basic navigation to simple routes`() = runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val store = createStore {
            module(createTestNavigationModule())
            coroutineContext(testDispatcher)
        }

        // Navigate to settings (root level screen)
        store.navigation { navigateTo("settings") }
        advanceUntilIdle()

        val state = store.selectState<NavigationState>().first()
        assertEquals("settings", state.currentEntry.screen.route)
        assertEquals("root", state.currentEntry.graphId)
        assertEquals(2, state.backStack.size) // [splash, settings]
    }

    @Test
    fun `test navigation to graph with startGraph reference`() = runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val store = createStore {
            module(createTestNavigationModule())
            coroutineContext(testDispatcher)
        }

        // Navigate to "home" - should resolve to news graph via startGraph("news")
        store.navigation { navigateTo("home") }
        advanceUntilIdle()

        val state = store.selectState<NavigationState>().first()

        // Key assertion: should end up in news graph, not home graph
        assertEquals("overview", state.currentEntry.screen.route)
        assertEquals("news", state.currentEntry.graphId) // Should be "news", not "home"
        assertEquals(2, state.backStack.size) // [splash, news/overview]
    }

    @Test
    fun `test clearBackStack with graph reference resolves correctly`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val testDispatcher = StandardTestDispatcher(testScheduler)
            val store = createStore {
                module(createTestNavigationModule())
                coroutineContext(testDispatcher)
            }

            // Navigate somewhere first
            store.navigation { navigateTo("settings") }
            advanceUntilIdle()

            // Clear backstack and navigate to home (should resolve to news)
            store.navigation {
                clearBackStack()
                navigateTo("home")
            }
            advanceUntilIdle()

            val state = store.selectState<NavigationState>().first()

            // Key assertions: should be in news graph with single entry
            assertEquals("overview", state.currentEntry.screen.route)
            assertEquals("news", state.currentEntry.graphId) // Critical: should be "news", not "home"
            assertEquals(1, state.backStack.size) // Should have only one entry after clear
        }

    @Test
    fun `test navigation to fully qualified paths`() = runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val store = createStore {
            module(createTestNavigationModule())
            coroutineContext(testDispatcher)
        }

        // Navigate to fully qualified path
        store.navigation {
            navigateTo("home/workspace/projects")
        }
        advanceUntilIdle()

        val state = store.selectState<NavigationState>().first()
        assertEquals("overview", state.currentEntry.screen.route) // project overview
        assertEquals("projects", state.currentEntry.graphId)
        assertEquals(2, state.backStack.size)
    }

    @Test
    fun `test navigation back functionality`() = runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val store = createStore {
            module(createTestNavigationModule())
            coroutineContext(testDispatcher)
        }

        // Navigate through multiple screens
        store.navigation { navigateTo("home") } // -> news/overview
        advanceUntilIdle()
        store.navigation {
            navigateTo("settings")
        }
        advanceUntilIdle()

        // Should have: [splash, news/overview, settings]
        var state = store.selectState<NavigationState>().first()
        assertEquals(3, state.backStack.size)
        assertEquals("settings", state.currentEntry.screen.route)

        // Navigate back
        store.navigateBack()
        advanceUntilIdle()

        state = store.selectState<NavigationState>().first()
        assertEquals(2, state.backStack.size)
        assertEquals("overview", state.currentEntry.screen.route)
        assertEquals("news", state.currentEntry.graphId)
    }

    @Test
    fun `test popUpTo with simple route name`() = runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val store = createStore {
            module(createTestNavigationModule())
            coroutineContext(testDispatcher)
        }

        // Build a backstack: splash -> home(news) -> workspace -> projects -> settings
        store.navigation { navigateTo("home") } // news/overview
        advanceUntilIdle()
        store.navigation {
            navigateTo("home/workspace")
        }
        advanceUntilIdle()
        store.navigation {
            navigateTo("home/workspace/projects")
        }
        advanceUntilIdle()
        store.navigation {
            navigateTo("settings")
        }
        advanceUntilIdle()

        // Should have 5 entries
        var state = store.selectState<NavigationState>().first()
        assertEquals(5, state.backStack.size)

        // PopUpTo the workspace screen
        store.navigation {
            navigateTo("home/leaderboard")
            popUpTo("workspace")
        }
        advanceUntilIdle()

        state = store.selectState<NavigationState>().first()

        // Should pop back to workspace (inclusive=false) and add leaderboard
        assertEquals("leaderboard", state.currentEntry.screen.route)
        assertTrue(state.backStack.any { it.screen.route == "workspace" })
        assertFalse(state.backStack.any { it.screen.route == "settings" })
    }

    @Test
    fun `test popUpTo with full path`() = runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val store = createStore {
            module(createTestNavigationModule())
            coroutineContext(testDispatcher)
        }

        // Build backstack with full paths
        store.navigation {
            navigateTo("home/news")
        }
        advanceUntilIdle()
        store.navigation {
            navigateTo("home/workspace")
        }
        advanceUntilIdle()
        store.navigation {
            navigateTo("home/workspace/projects")
        }
        advanceUntilIdle()

        // PopUpTo with full path
        store.navigation {
            popUpTo("home/news")
            navigateTo("settings")
        }
        advanceUntilIdle()

        val state = store.selectState<NavigationState>().first()
        assertEquals("settings", state.currentEntry.screen.route)
        assertTrue(state.backStack.any { it.screen.route == "overview" && it.graphId == "news" })
        assertFalse(state.backStack.any { it.screen.route == "workspace" })
    }

    @Test
    fun `test popUpTo inclusive removes target route`() = runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val store = createStore {
            module(createTestNavigationModule())
            coroutineContext(testDispatcher)
        }

        // Build backstack
        store.navigation { navigateTo("home") }
        advanceUntilIdle()
        store.navigation {
            navigateTo("settings")
        }
        advanceUntilIdle()

        // PopUpTo inclusive should remove the target as well
        store.navigation {
            navigateTo("home/leaderboard")
            popUpTo("splash", inclusive = true)
        }
        advanceUntilIdle()

        val state = store.selectState<NavigationState>().first()
        assertEquals("leaderboard", state.currentEntry.screen.route)
        assertFalse(state.backStack.any { it.screen.route == "splash" })
        assertEquals(1, state.backStack.size) // Only leaderboard should remain
    }

    @Test
    fun `test replace navigation`() = runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val store = createStore {
            module(createTestNavigationModule())
            coroutineContext(testDispatcher)
        }

        // Navigate somewhere
        store.navigation { navigateTo("home") }
        advanceUntilIdle()

        val sizeBefore = store.selectState<NavigationState>().first().backStack.size

        // Replace current screen
        store.navigation { navigateTo("settings", replaceCurrent = true) }
        advanceUntilIdle()

        val state = store.selectState<NavigationState>().first()
        assertEquals("settings", state.currentEntry.screen.route)
        assertEquals(sizeBefore, state.backStack.size) // Size should remain the same
        assertFalse(state.backStack.any { it.screen.route == "overview" }) // Previous screen should be gone
    }

    @Test
    fun `test navigation with parameters`() = runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val store = createStore {
            module(createTestNavigationModule())
            coroutineContext(testDispatcher)
        }

        val testParams = Params.of("userId" to "123", "source" to "deep_link")

        store.navigation {
            navigateTo("home"){
                params(testParams)
            }
        }
        advanceUntilIdle()

        val state = store.selectState<NavigationState>().first()
        assertEquals("123", state.currentEntry.params.getString("userId"))
        assertEquals("deep_link", state.currentEntry.params.getString("source"))
    }

    @Test
    fun `test parameter persistence through navigation`() = runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val store = createStore {
            module(createTestNavigationModule())
            coroutineContext(testDispatcher)
        }

        // Navigate with parameters
        store.navigation {
            navigateTo("home"){
                params(Params.of("sessionId" to "abc123"))
            }
        }
        advanceUntilIdle()

        // Navigate away and back
        store.navigation {
            navigateTo("settings")
        }
        advanceUntilIdle()
        store.navigateBack()
        advanceUntilIdle()

        val state = store.selectState<NavigationState>().first()
        assertEquals("overview", state.currentEntry.screen.route)
        assertEquals("abc123", state.currentEntry.params.getString("sessionId")) // Params should persist
    }

    @Test
    fun `test canGoBack functionality`() = runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val store = createStore {
            module(createTestNavigationModule())
            coroutineContext(testDispatcher)
        }

        // Initial state - should be able to go back (from splash)
        var state = store.selectState<NavigationState>().first()
        assertFalse(state.canGoBack) // Only one entry initially

        // Navigate somewhere
        store.navigation {
            navigateTo("settings")
        }
        advanceUntilIdle()

        state = store.selectState<NavigationState>().first()
        assertTrue(state.canGoBack) // Now we can go back

        // Clear backstack
        store.navigation {
            clearBackStack()
            navigateTo("home")
        }
        advanceUntilIdle()

        state = store.selectState<NavigationState>().first()
        assertFalse(state.canGoBack) // Single entry again after clear
    }

    @Test
    fun `test complex navigation scenario matching user's setup`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val testDispatcher = StandardTestDispatcher(testScheduler)
            val store = createStore {
                module(createTestNavigationModule())
                coroutineContext(testDispatcher)
            }

            // Simulate user's problematic scenario
            store.navigation {
                navigateTo("home")
            }
            advanceUntilIdle()

            var state = store.selectState<NavigationState>().first()
            assertEquals("news", state.currentEntry.graphId)
            assertEquals("overview", state.currentEntry.screen.route)

            // Navigate to workspace
            store.navigation {
                navigateTo("home/workspace")
            }
            advanceUntilIdle()

            // Use the problematic clearBackStack operation
            store.navigation {
                clearBackStack()
                navigateTo("home")
            }
            advanceUntilIdle()

            state = store.selectState<NavigationState>().first()

            // Critical assertions for the bug fix
            assertEquals("news", state.currentEntry.graphId) // Should be "news", not "home"
            assertEquals("overview", state.currentEntry.screen.route)
            assertEquals(1, state.backStack.size)
        }

    @Test
    fun `test navigation state consistency after multiple operations`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val testDispatcher = StandardTestDispatcher(testScheduler)
            val store = createStore {
                module(createTestNavigationModule())
                coroutineContext(testDispatcher)
            }

            // Perform multiple operations
            store.navigation {
                navigateTo("home")
            }
            advanceUntilIdle()
            store.navigation {
                navigateTo("home/workspace/projects")
            }
            advanceUntilIdle()
            store.navigation {
                navigateTo("settings")
            }
            advanceUntilIdle()
            store.navigateBack()
            advanceUntilIdle()
            store.navigation {
                popUpTo("home")
                navigateTo("home/leaderboard")
            }
            advanceUntilIdle()

            val state = store.selectState<NavigationState>().first()

            // Verify consistency
            assertEquals("leaderboard", state.currentEntry.screen.route)
            assertEquals("leaderboard", state.currentEntry.graphId)

            // Verify current entry is always the last in backstack
            assertEquals(state.currentEntry, state.backStack.last())

            // Verify all entries have valid graph IDs
            state.backStack.forEach { entry ->
                assertTrue(entry.graphId.isNotEmpty())
                assertTrue(entry.screen.route.isNotEmpty())
            }
        }

    @Test
    fun `test error handling for invalid routes`() = runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val store = createStore {
            module(createTestNavigationModule())
            coroutineContext(testDispatcher)
        }

        // Try to navigate to non-existent route
        assertFailsWith<RouteNotFoundException>() {
            store.navigation {
                navigateTo("nonexistent")
            }
            advanceUntilIdle()
        }

        // State should remain unchanged
        val state = store.selectState<NavigationState>().first()
        assertEquals("splash", state.currentEntry.screen.route)
    }

    @Test
    fun `test popUpTo with non-existent route falls back gracefully`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val testDispatcher = StandardTestDispatcher(testScheduler)
            val store = createStore {
                module(createTestNavigationModule())
                coroutineContext(testDispatcher)
            }

            store.navigation {
                navigateTo("home")
            }
            advanceUntilIdle()
            store.navigation {
                navigateTo("settings")
            }
            advanceUntilIdle()

            // PopUpTo non-existent route should just add to backstack
            assertFailsWith<RouteNotFoundException> {
                store.navigation {
                    popUpTo("nonexistent")
                    navigateTo("home/leaderboard")
                }
            }
            advanceUntilIdle()
        }

    @Test
    fun `test navigation with content URI parameters preserves encoding`() = runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val contentUri = "content://com.google.android.apps.docs.storage/document/acc%3D9%3Bdoc%3Dencoded%3Dq3kaiKNyhntVpRozJ-eI-R1HG6TXaN_W7vafc216hOscCol3D2WPTL4kvwY%3D"
        ReaktivDebug.enable()
        val store = createStore {
            module(createTestNavigationModule())
            coroutineContext(testDispatcher)
        }
        
        // Navigate with the content URI as a parameter

        store.navigation {
            navigateTo("home") {
                params(Params.of("fileUri" to contentUri))
            }
        }
        advanceUntilIdle()
        
        val navigationState = store.selectState<NavigationState>().first()
        val currentEntry = navigationState.currentEntry
        
        // Verify the parameter was preserved correctly after encoding/decoding
        assertEquals("overview", currentEntry.screen.route)
        println("HERPADERPA - currentEntry params: ${currentEntry.params}")
        assertEquals(contentUri, currentEntry.params.getString("fileUri"))
        
        // Verify the content URI structure is intact using the decoded value
        val retrievedUri = currentEntry.params.getString("fileUri")!!
        assertTrue(retrievedUri.startsWith("content://"))
        assertTrue(retrievedUri.contains("acc%3D9%3Bdoc%3Dencoded%3D"))
        assertTrue(retrievedUri.contains("%3D")) // Equals signs remain encoded in original URI
        assertTrue(retrievedUri.contains("%3B")) // Semicolons remain encoded in original URI
    }
}