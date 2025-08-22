import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import io.github.syrou.reaktiv.core.createStore
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
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.DurationUnit
import kotlin.time.toDuration

@OptIn(ExperimentalCoroutinesApi::class)
class UnifiedNavigationBuilderTest {

    @Serializable
    data class TestUser(val id: String, val name: String)

    private fun createTestNavigationModule() = createNavigationModule {
        rootGraph {
            startScreen(SplashScreen)
            screens(SettingsScreen)

            graph("home") {
                startScreen(HomeScreen)

                graph("news") {
                    startScreen(NewsOverviewScreen)
                    screens(NewsListScreen)
                }

                graph("workspace") {
                    startScreen(WorkspaceOverviewScreen)

                    graph("projects") {
                        startScreen(ProjectOverviewScreen)
                        screens(ProjectTaskScreen)
                    }
                }
            }
        }
    }

    @Test
    fun `test navigation to screen object resolves correct path`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val testDispatcher = StandardTestDispatcher(testScheduler)
            val store = createStore {
                module(createTestNavigationModule())
                coroutineContext(testDispatcher)
            }

            // Navigate to settings screen (root level)
            store.navigation {
                navigateTo<SettingsScreen>()
            }
            advanceUntilIdle()

            val state = store.selectState<NavigationState>().first()
            assertEquals("settings", state.currentEntry.screen.route)
            assertEquals("root", state.currentEntry.graphId)
            assertEquals("settings", state.currentFullPath)
        }

    @Test
    fun `test navigation to nested screen object resolves correct path`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val testDispatcher = StandardTestDispatcher(testScheduler)
            val store = createStore {
                module(createTestNavigationModule())
                coroutineContext(testDispatcher)
            }

            // Navigate to project overview (deeply nested screen)
            store.navigation {
                navigateTo<ProjectOverviewScreen>()
            }
            advanceUntilIdle()

            val state = store.selectState<NavigationState>().first()
            assertEquals("project-overview", state.currentEntry.screen.route)
            assertEquals("projects", state.currentEntry.graphId)
            assertEquals("home/workspace/projects/project-overview", state.currentFullPath)
        }

    @Test
    fun `test navigation with parameters`() = runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val store = createStore {
            module(createTestNavigationModule())
            coroutineContext(testDispatcher)
        }

        // Navigate with various parameter types
        store.navigation {
            navigateTo<ProjectOverviewScreen> {
                putString("projectId", "proj-123")
                putBoolean("isNew", true)
                putInt("priority", 5)
                put("user", TestUser("user-456", "John Doe"))
            }
        }
        advanceUntilIdle()

        val state = store.selectState<NavigationState>().first()
        assertEquals("proj-123", state.currentEntry.params["projectId"])
        assertEquals(true, state.currentEntry.params["isNew"])
        assertEquals(5, state.currentEntry.params["priority"])
        assertTrue(state.currentEntry.params.containsKey("user"))
    }

    @Test
    fun `test navigation with parameter builder`() = runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val store = createStore {
            module(createTestNavigationModule())
            coroutineContext(testDispatcher)
        }

        store.navigation {
            navigateTo<SettingsScreen> {
                putString("theme", "dark")
                putInt("userId", 123)
                put("user", TestUser("123", "Jane"))
            }
        }
        advanceUntilIdle()

        val state = store.selectState<NavigationState>().first()
        assertEquals("dark", state.currentEntry.params["theme"])
        assertEquals(123, state.currentEntry.params["userId"])
        assertTrue(state.currentEntry.params.containsKey("user"))
    }

    @Test
    fun `test popUpTo with screen object`() = runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val store = createStore {
            module(createTestNavigationModule())
            coroutineContext(testDispatcher)
        }

        // Build backstack
        store.navigation { navigateTo<HomeScreen>() }
        advanceUntilIdle()
        store.navigation { navigateTo<NewsOverviewScreen>() }
        advanceUntilIdle()
        store.navigation { navigateTo<WorkspaceOverviewScreen>() }
        advanceUntilIdle()

        // PopUpTo home screen
        store.navigation {
            navigateTo<SettingsScreen>()
            popUpTo<HomeScreen>(inclusive = false)
        }
        advanceUntilIdle()

        val state = store.selectState<NavigationState>().first()
        assertEquals("settings", state.currentEntry.screen.route)
        assertTrue(state.backStack.any { it.screen.route == "home-overview" })
        assertTrue(state.backStack.none { it.screen.route == "news-overview" })
        assertTrue(state.backStack.none { it.screen.route == "workspace-overview" })
    }

    @Test
    fun `test navigateTo with replaceCurrent screen object`() = runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val store = createStore {
            module(createTestNavigationModule())
            coroutineContext(testDispatcher)
        }

        // Navigate to a screen first
        store.navigation { navigateTo<HomeScreen>() }
        advanceUntilIdle()

        val sizeBefore = store.selectState<NavigationState>().first().backStack.size

        // Replace with settings screen
        store.navigation {
            navigateTo<SettingsScreen>(replaceCurrent = true) {
                putString("source", "replace")
            }
        }
        advanceUntilIdle()

        val state = store.selectState<NavigationState>().first()
        assertEquals("settings", state.currentEntry.screen.route)
        assertEquals(sizeBefore, state.backStack.size) // Size should remain the same
        assertEquals("replace", state.currentEntry.params["source"])
        assertTrue(state.backStack.none { it.screen.route == "home-overview" }) // Old screen should be gone
    }

    @Test
    fun `test clearBackStack with screen object`() = runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val store = createStore {
            module(createTestNavigationModule())
            coroutineContext(testDispatcher)
        }

        // Build backstack
        store.navigation { navigateTo<HomeScreen>() }
        advanceUntilIdle()
        store.navigation { navigateTo<SettingsScreen>() }
        advanceUntilIdle()

        // Clear and navigate to workspace
        store.navigation {
            clearBackStack()
            navigateTo<WorkspaceOverviewScreen>()
        }
        advanceUntilIdle()

        val state = store.selectState<NavigationState>().first()
        assertEquals("workspace-overview", state.currentEntry.screen.route)
        assertEquals(1, state.backStack.size) // Only current entry should remain
        assertEquals("home/workspace/workspace-overview", state.currentFullPath)
    }

    @Test
    fun `test path parameter extraction and merging`() = runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
        val testDispatcher = StandardTestDispatcher(testScheduler)

        // Create a screen with parameterized route


        val navigationModule = createNavigationModule {
            rootGraph {
                startScreen(SplashScreen)
                screens(SplashScreen, SettingsScreen)

                graph("home") {
                    startScreen(HomeScreen)

                    graph("leaderboard") {
                        startScreen(WorkspaceOverviewScreen)
                        screens(StatsScreen)
                    }
                }
            }
        }

        val store = createStore {
            module(navigationModule)
            coroutineContext(testDispatcher)
        }

        // Navigate to parameterized route with both path and query parameters
        store.navigation {
            navigateTo("home/leaderboard/stats/individual") {
                putString("playerId", "player-123")
                putBoolean("showDetails", true)
                put("playerData", TestUser("player-123", "John Player"))
            }
        }
        advanceUntilIdle()

        val state = store.selectState<NavigationState>().first()

        // Verify the screen and basic navigation
        assertEquals("stats/{type}", state.currentEntry.screen.route)
        assertEquals("leaderboard", state.currentEntry.graphId)
        assertEquals("home/leaderboard/stats/individual", state.currentFullPath)

        // Verify path parameter extraction
        assertEquals("individual", state.currentEntry.params["type"]) // ✅ Path parameter

        // Verify user parameters are preserved
        assertEquals("player-123", state.currentEntry.params["playerId"]) // ✅ User parameter
        assertEquals(true, state.currentEntry.params["showDetails"]) // ✅ User parameter
        assertTrue(state.currentEntry.params.containsKey("playerData")) // ✅ User parameter

        // Verify parameter count (should have all 4: type + 3 user params)
        assertEquals(4, state.currentEntry.params.size)
    }

    @Test
    fun `test currentFullPath with parameterized routes`() = runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
        val testDispatcher = StandardTestDispatcher(testScheduler)

        // Create screens with different parameterized routes
        val userProfileScreen = object : Screen {
            override val route = "user/{userId}/profile"
            override val enterTransition = NavTransition.None
            override val exitTransition = NavTransition.None
            override val requiresAuth = false

            @Composable
            override fun Content(params: Params) {
                Text("User Profile")
            }
        }

        val postDetailsScreen = object : Screen {
            override val route = "post/{postId}/comments/{commentId}"
            override val enterTransition = NavTransition.None
            override val exitTransition = NavTransition.None
            override val requiresAuth = false

            @Composable
            override fun Content(params: Params) {
                Text("Post Details")
            }
        }

        val navigationModule = createNavigationModule {
            rootGraph {
                startScreen(SplashScreen)
                screens(userProfileScreen, postDetailsScreen)
            }
        }

        val store = createStore {
            module(navigationModule)
            coroutineContext(testDispatcher)
        }

        // Test single parameter route
        store.navigation {
            navigateTo("user/john-doe/profile") {
                putString("tab", "settings")
            }
        }
        advanceUntilIdle()

        var state = store.selectState<NavigationState>().first()

        // Should show resolved path, not template
        assertEquals("user/john-doe/profile", state.currentFullPath)
        assertEquals("john-doe", state.currentEntry.params["userId"])
        assertEquals("settings", state.currentEntry.params["tab"])

        // Test multiple parameter route
        store.navigation {
            navigateTo("post/blog-123/comments/comment-456") {
                putString("highlight", "true")
            }
        }
        advanceUntilIdle()

        state = store.selectState<NavigationState>().first()

        // Should show resolved path with both parameters substituted
        assertEquals("post/blog-123/comments/comment-456", state.currentFullPath)
        assertEquals("blog-123", state.currentEntry.params["postId"])
        assertEquals("comment-456", state.currentEntry.params["commentId"])
        assertEquals("true", state.currentEntry.params["highlight"])
    }

    @Test
    fun `test multiple path parameters`() = runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
        val testDispatcher = StandardTestDispatcher(testScheduler)

        // Create a screen with multiple path parameters

        val navigationModule = createNavigationModule {
            rootGraph {
                startScreen(SplashScreen)
                screens(MultiParamScreen)
            }
        }

        val store = createStore {
            module(navigationModule)
            coroutineContext(testDispatcher)
        }

        // Navigate to route with multiple path parameters
        store.navigation {
            navigateTo("company/acme-corp/user/john-doe/profile") {
                putString("tab", "settings")
                putInt("version", 2)
            }
        }
        advanceUntilIdle()

        val state = store.selectState<NavigationState>().first()

        // Verify multiple path parameters
        assertEquals("acme-corp", state.currentEntry.params["companyId"]) // ✅ Path parameter 1
        assertEquals("john-doe", state.currentEntry.params["userId"]) // ✅ Path parameter 2

        // Verify user parameters
        assertEquals("settings", state.currentEntry.params["tab"]) // ✅ User parameter
        assertEquals(2, state.currentEntry.params["version"]) // ✅ User parameter

        // Should have all 4 parameters: 2 path + 2 user
        assertEquals(4, state.currentEntry.params.size)
    }

    @Test
    fun `test mixed string and screen navigation`() = runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val store = createStore {
            module(createTestNavigationModule())
            coroutineContext(testDispatcher)
        }

        // Navigate using string path
        store.navigation {
            navigateTo("home/news") {
                putString("source", "string")
            }
        }
        advanceUntilIdle()

        // Then navigate with screen object and popUpTo using string
        store.navigation {
            navigateTo<ProjectTaskScreen>()
            popUpTo("home/news")
        }
        advanceUntilIdle()

        val state = store.selectState<NavigationState>().first()
        assertEquals("project-tasks", state.currentEntry.screen.route)
        assertEquals("projects", state.currentEntry.graphId)
        assertTrue(state.backStack.any { it.screen.route == "news-overview" && it.graphId == "news" })
    }

    @Test
    fun `test preferred graph disambiguation`() = runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val store = createStore {
            module(createTestNavigationModule())
            coroutineContext(testDispatcher)
        }

        // Navigate to project overview with preferred graph
        store.navigation {
            navigateTo<ProjectOverviewScreen> {
                putString("context", "projects")
            }
        }
        advanceUntilIdle()

        val state = store.selectState<NavigationState>().first()
        assertEquals("project-overview", state.currentEntry.screen.route)
        assertEquals("projects", state.currentEntry.graphId)
        assertEquals("projects", state.currentEntry.params["context"])
        assertEquals("home/workspace/projects/project-overview", state.currentFullPath)
    }

    @Test
    fun `test navigation state extensions`() = runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val store = createStore {
            module(createTestNavigationModule())
            coroutineContext(testDispatcher)
        }

        store.navigation { navigateTo<ProjectTaskScreen>() }
        advanceUntilIdle()

        val state = store.selectState<NavigationState>().first()

        // Test extension properties
        assertEquals("home/workspace/projects/project-tasks", state.currentFullPath)
        assertEquals(listOf("home", "workspace", "projects", "project-tasks"), state.currentPathSegments)
        assertEquals(listOf("home", "workspace", "projects"), state.currentGraphHierarchy)
        assertEquals(4, state.navigationDepth)

        // Test helper methods would be tested here
        assertTrue(state.allAvailableNavigatables.isNotEmpty())
    }

    @Test
    fun `test error handling for non-existent screen`() = runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val store = createStore {
            module(createTestNavigationModule())
            coroutineContext(testDispatcher)
        }

        class NonExistentScreen : Screen {
            override val route = "nonexistant"
            override val enterTransition = NavTransition.None
            override val exitTransition = NavTransition.None
            override val requiresAuth = false

            @Composable
            override fun Content(params: Params) {
                Text("nonexistant")
            }
        }

        // Should throw RouteNotFoundException
        assertFailsWith<RouteNotFoundException> {
            store.navigation {
                navigateTo<NonExistentScreen>()
            }
            advanceUntilIdle()
        }
    }

    @Test
    fun `test error handling for invalid configuration`() = runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val store = createStore {
            module(createTestNavigationModule())
            coroutineContext(testDispatcher)
        }

        // Should throw IllegalArgumentException for combining clearBackStack with popUpTo
        assertFailsWith<IllegalArgumentException> {
            store.navigation {
                navigateTo<HomeScreen>()
                clearBackStack()
                popUpTo<SettingsScreen>()
            }
            advanceUntilIdle()
        }
    }

    @Test
    fun `test complex navigation scenario`() = runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val store = createStore {
            module(createTestNavigationModule())
            coroutineContext(testDispatcher)
        }

        // Build complex backstack
        store.navigation { navigateTo<HomeScreen>() }
        advanceUntilIdle()
        store.navigation { navigateTo<NewsOverviewScreen>() }
        advanceUntilIdle()
        store.navigation { navigateTo<WorkspaceOverviewScreen>() }
        advanceUntilIdle()
        store.navigation { navigateTo<ProjectTaskScreen>() }
        advanceUntilIdle()

        // Complex navigation: navigate to settings, pop up to home (inclusive)
        store.navigation {
            navigateTo<SettingsScreen> {
                putString("source", "complex")
                putLong("timestamp", 1234567890L)
            }
            popUpTo<HomeScreen>(inclusive = true)
        }
        advanceUntilIdle()

        val state = store.selectState<NavigationState>().first()

        assertEquals("settings", state.currentEntry.screen.route)
        assertEquals("root", state.currentEntry.graphId)
        assertEquals("complex", state.currentEntry.params["source"])
        assertEquals(1234567890L, state.currentEntry.params["timestamp"])

        // Should have: [settings] (home and everything after should be gone)
        assertEquals(2, state.backStack.size) // [splash, settings]
        assertTrue(state.backStack.none { it.screen.route == "home-overview" })
        assertTrue(state.backStack.none { it.screen.route == "news-overview" })
        assertTrue(state.backStack.none { it.screen.route == "workspace-overview" })
        assertTrue(state.backStack.none { it.screen.route == "project-tasks" })
    }
}