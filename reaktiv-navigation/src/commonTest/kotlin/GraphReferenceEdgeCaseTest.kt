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
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.DurationUnit
import kotlin.time.toDuration


@OptIn(ExperimentalCoroutinesApi::class)
class GraphReferenceEdgeCaseTest {

    @BeforeTest
    fun beforeTest() {
        ReaktivDebug.enable()
    }

    // Create screens that match the user's actual setup
    private fun createScreen(route: String, title: String = route) = object : Screen {
        override val route = route
        override val enterTransition = NavTransition.None
        override val exitTransition = NavTransition.None
        override val requiresAuth = false

        @Composable
        override fun Content(params: Params) {
            Text(title)
        }
    }

    // Screens matching the user's structure
    private val splashScreen = createScreen("splash", "Splash Screen")
    private val settingsScreen = createScreen("settings", "Settings Screen")
    // This is the "overview" the user mentioned
    private val newsOverviewScreen =
        createScreen("overview", "News Overview")
    private val newsListScreen = createScreen("list", "News List")
    private val workspaceScreen = createScreen("workspace", "Workspace Screen")
    private val projectOverviewScreen = createScreen("overview", "Project Overview")
    private val projectTasksScreen = createScreen("tasks", "Project Tasks")
    private val leaderboardScreen = createScreen("leaderboard", "Leaderboard Screen")


    private fun createUserExactNavigationModule() = createNavigationModule {
        rootGraph {
            startScreen(splashScreen)
            screens(splashScreen, settingsScreen)

            graph("home") {
                // This is the key line that was causing issues
                startGraph("news")

                graph("news") {
                    // This should be the final destination
                    startScreen(newsOverviewScreen)
                    screens(newsOverviewScreen, newsListScreen)
                }

                graph("workspace") {
                    startScreen(workspaceScreen)

                    graph("projects") {
                        startScreen(projectOverviewScreen)
                        screens(projectOverviewScreen, projectTasksScreen)
                    }
                }

                graph("leaderboard") {
                    startScreen(leaderboardScreen)
                }
            }
        }
    }

    @Test
    fun `test clearBackStack with startGraph reference resolves to correct graph - USER BUG REPRODUCTION`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val testDispatcher = StandardTestDispatcher(testScheduler)
            val store = createStore {
                module(createUserExactNavigationModule())
                coroutineContext(testDispatcher)
            }

            // This is the exact user scenario that was failing:
            // store.clearBackStack { setRoot("home") }
            // Should end up with news/overview, not home/overview

            store.navigation {
                clearBackStack()
                navigateTo("home")
            }
            advanceUntilIdle()

            val state = store.selectState<NavigationState>().first()

            // CRITICAL ASSERTIONS - These were failing before the fix
            assertEquals("overview", state.currentEntry.screen.route)
            // This was "home" before the fix!
            assertEquals("news", state.currentEntry.graphId)
            // Should have cleared to single entry
            assertEquals(1, state.backStack.size)
            assertFalse(state.canGoBack)

            // Debug output for verification
            println("User bug test result:")
            println("- Screen: ${state.currentEntry.screen.route}")
            println("- Graph: ${state.currentEntry.graphId}")
            println("- Expected: news/overview")
            println("- SUCCESS: ${state.currentEntry.graphId == "news" && state.currentEntry.screen.route == "overview"}")
        }

    @Test
    fun `test direct navigation to startGraph reference resolves correctly`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val testDispatcher = StandardTestDispatcher(testScheduler)
            val store = createStore {
                module(createUserExactNavigationModule())
                coroutineContext(testDispatcher)
            }

            // Direct navigation to "home" should resolve to news graph
            store.navigation { navigateTo("home") }
            advanceUntilIdle()

            val state = store.selectState<NavigationState>().first()

            assertEquals("overview", state.currentEntry.screen.route)
            // Should resolve to news, not home
            assertEquals("news", state.currentEntry.graphId)
            // [splash, news/overview]
            assertEquals(2, state.backStack.size)
        }

    @Test
    fun `test complex navigation sequence with graph references`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val testDispatcher = StandardTestDispatcher(testScheduler)
            val store = createStore {
                module(createUserExactNavigationModule())
                coroutineContext(testDispatcher)
            }

            // Complex sequence that might have broken before
            // -> news/overview
            store.navigation { navigateTo("home") }
            advanceUntilIdle()
            // -> workspace/workspace
            store.navigation { navigateTo("home/workspace") }
            advanceUntilIdle()
            // -> root/settings
            store.navigation { navigateTo("settings") }
            advanceUntilIdle()

            // Now use the problematic clearBackStack operation
            store.navigation {
                clearBackStack()
                navigateTo("home")
            }
            advanceUntilIdle()

            val state = store.selectState<NavigationState>().first()

            // Should resolve to news/overview, not home/overview
            assertEquals("overview", state.currentEntry.screen.route)
            assertEquals("news", state.currentEntry.graphId)
            assertEquals(1, state.backStack.size)
        }

    @Test
    fun `test popUpTo with graph reference resolution`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val testDispatcher = StandardTestDispatcher(testScheduler)
            val store = createStore {
                module(createUserExactNavigationModule())
                coroutineContext(testDispatcher)
            }

            // Build backstack with mixed graphs
            // news/overview
            store.navigation { navigateTo("home") }
            advanceUntilIdle()
            // workspace/workspace
            store.navigation { navigateTo("home/workspace") }
            advanceUntilIdle()
            // root/settings
            store.navigation { navigateTo("settings") }
            advanceUntilIdle()

            // PopUpTo the "home" screen (which should resolve to news/overview)
            store.navigation {
                navigateTo("home/leaderboard")
                popUpTo("home")
            }
            advanceUntilIdle()

            val state = store.selectState<NavigationState>().first()

            assertEquals("leaderboard", state.currentEntry.screen.route)
            assertEquals("leaderboard", state.currentEntry.graphId)

            // Should have popped back to the news/overview entry (the resolved "home")
            assertTrue(state.backStack.any { it.screen.route == "overview" && it.graphId == "news" })
            assertFalse(state.backStack.any { it.screen.route == "workspace" })
            assertFalse(state.backStack.any { it.screen.route == "settings" })
        }

    @Test
    fun `test nested graph reference chain resolution`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            // Create a more complex reference chain for edge case testing
            val screenA = createScreen("screen-a")
            val screenB = createScreen("screen-b")
            val screenC = createScreen("screen-c")

            val navigationModule = createNavigationModule {
                rootGraph {
                    startScreen(splashScreen)
                    screens(splashScreen)

                    graph("level1") {
                        // level1 -> level2
                        startGraph("level2")

                        graph("level2") {
                            // level2 -> level3
                            startGraph("level3")

                            graph("level3") {
                                // Final destination
                                startScreen(screenC)
                                screens(screenC)
                            }
                        }
                    }
                }
            }

            val testDispatcher = StandardTestDispatcher(testScheduler)
            val store = createStore {
                module(navigationModule)
                coroutineContext(testDispatcher)
            }

            // Navigate to level1, which should resolve through the chain to level3/screen-c
            store.navigation { navigateTo("level1") }
            advanceUntilIdle()

            val state = store.selectState<NavigationState>().first()

            assertEquals("screen-c", state.currentEntry.screen.route)
            // Should be final graph in chain
            assertEquals("level3", state.currentEntry.graphId)
        }

    @Test
    fun `test route resolution consistency across operations`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val testDispatcher = StandardTestDispatcher(testScheduler)
            val store = createStore {
                module(createUserExactNavigationModule())
                coroutineContext(testDispatcher)
            }
            store.navigation { navigateTo("splash") }
            // Direct navigation
            store.navigation { navigateTo("home") }
            advanceUntilIdle()
            val directNav = store.selectState<NavigationState>().first().currentEntry

            // Clear and navigate
            store.navigation {
                clearBackStack()
                navigateTo("splash")
            }
            store.navigation { navigateTo("home") }
            advanceUntilIdle()
            val clearNav = store.selectState<NavigationState>().first().currentEntry

            // PopUpTo and navigate (build some backstack first)
            store.navigation { navigateTo("settings") }
            advanceUntilIdle()
            store.navigation { 
                popUpTo("splash")
                navigateTo("home")
            }

            advanceUntilIdle()
            val popUpToNav = store.selectState<NavigationState>().first().currentEntry

            // All should resolve to the same screen and graph
            assertEquals(directNav.screen.route, clearNav.screen.route)
            assertEquals(directNav.graphId, clearNav.graphId)
            assertEquals(directNav.screen.route, popUpToNav.screen.route)
            assertEquals(directNav.graphId, popUpToNav.graphId)

            // All should be news/overview
            assertEquals("overview", directNav.screen.route)
            assertEquals("news", directNav.graphId)
        }

    @Test
    fun `test parameter preservation through graph reference resolution`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val testDispatcher = StandardTestDispatcher(testScheduler)
            val store = createStore {
                module(createUserExactNavigationModule())
                coroutineContext(testDispatcher)
            }

            val testParams = Params.of("sessionId" to "abc123", "source" to "deep_link")

            // Navigate to "home" with parameters - should resolve to news/overview with params
            store.navigation { 
                navigateTo("home") {
                    testParams.toMap().forEach { (k, v) -> putRaw(k, v) }
                }
            }
            advanceUntilIdle()

            val state = store.selectState<NavigationState>().first()

            assertEquals("overview", state.currentEntry.screen.route)
            assertEquals("news", state.currentEntry.graphId)
            assertEquals("abc123", state.currentEntry.params.getString("sessionId"))
            assertEquals("deep_link", state.currentEntry.params.getString("source"))
        }

    @Test
    fun `test graph layout context preservation after reference resolution`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            // Test that the effective graph ID is used for layout application
            val testDispatcher = StandardTestDispatcher(testScheduler)
            val store = createStore {
                module(createUserExactNavigationModule())
                coroutineContext(testDispatcher)
            }

            store.navigation { navigateTo("home") }
            advanceUntilIdle()

            val state = store.selectState<NavigationState>().first()

            // The effective graph should be "news" for layout purposes
            assertEquals("news", state.currentEntry.graphId)

            // This ensures that if there were layouts defined on the news graph,
            // they would be applied correctly, not the home graph layouts
        }

    @Test
    fun `test error handling with malformed graph references`() = runTest(
        timeout = 5.toDuration(DurationUnit.SECONDS)
    ) {
        // Test what happens with a broken reference
        val brokenModule = createNavigationModule {
            rootGraph {
                startScreen(splashScreen)
                screens(splashScreen)

                graph("broken") {
                    // This graph doesn't exist
                    startGraph("nonexistent")
                }
            }
        }

        val testDispatcher = StandardTestDispatcher(testScheduler)
        val store = createStore {
            module(brokenModule)
            coroutineContext(testDispatcher)
        }

        // Should handle broken reference gracefully
        assertFailsWith<RouteNotFoundException> {
            store.navigation { navigateTo("broken") }
            advanceUntilIdle()
        }

        // If it doesn't throw, verify we're still in a valid state
        val state = store.selectState<NavigationState>().first()
        assertTrue(state.backStack.isNotEmpty())
        assertTrue(state.currentEntry.screen.route.isNotEmpty())

    }
}