import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import io.github.syrou.reaktiv.core.createStore
import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.createNavigationModule
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.exception.RouteNotFoundException
import io.github.syrou.reaktiv.navigation.extension.clearBackStack
import io.github.syrou.reaktiv.navigation.extension.navigate
import io.github.syrou.reaktiv.navigation.extension.navigateBack
import io.github.syrou.reaktiv.navigation.extension.replaceWith
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.DurationUnit
import kotlin.time.toDuration

@OptIn(ExperimentalCoroutinesApi::class)
class NavigationLogicTest {
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

    private val homeScreen = createScreen("home", "Home")
    private val profileScreen = createScreen("profile", "Profile")
    private val settingsScreen = createScreen("settings", "Settings")
    private val aboutScreen = createScreen("about", "About")
    private val newsScreen = createScreen("news", "News")
    private val workspaceScreen = createScreen("workspace", "Workspace")

    private fun createSimpleNavigationModule() = createNavigationModule {
        rootGraph {
            startScreen(homeScreen)
            screens(homeScreen, profileScreen, settingsScreen, aboutScreen)

            graph("content") {
                startScreen(newsScreen)
                screens(newsScreen, workspaceScreen)
            }
        }
    }

    @Test
    fun `test popUpTo with non-inclusive removes everything after target`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val testDispatcher = StandardTestDispatcher(testScheduler)
            val store = createStore {
                module(createSimpleNavigationModule())
                coroutineContext(testDispatcher)
            }
            store.navigate("profile")
            advanceUntilIdle()
            store.navigate("settings")
            advanceUntilIdle()
            store.navigate("about")
            advanceUntilIdle()
            var state = store.selectState<NavigationState>().first()
            assertEquals(4, state.backStack.size)
            store.navigate("content/news") {
                popUpTo("profile", inclusive = false)
            }
            advanceUntilIdle()

            state = store.selectState<NavigationState>().first()
            assertEquals(3, state.backStack.size)
            assertEquals("news", state.currentEntry.screen.route)
            assertTrue(state.backStack.any { it.screen.route == "profile" }) // Profile should still be there
            assertFalse(state.backStack.any { it.screen.route == "settings" }) // Settings should be gone
            assertFalse(state.backStack.any { it.screen.route == "about" }) // About should be gone
        }

    @Test
    fun `test popUpTo with inclusive removes target and everything after`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val testDispatcher = StandardTestDispatcher(testScheduler)
            val store = createStore {
                module(createSimpleNavigationModule())
                coroutineContext(testDispatcher)
            }

            // Build backstack: home -> profile -> settings -> about
            store.navigate("profile")
            advanceUntilIdle()
            store.navigate("settings")
            advanceUntilIdle()
            store.navigate("about")
            advanceUntilIdle()

            // PopUpTo profile (inclusive) and navigate to news
            store.navigate("content/news") {
                popUpTo("profile", inclusive = true)
            }
            advanceUntilIdle()

            val state = store.selectState<NavigationState>().first()

            // Should have: [home, news]
            assertEquals(2, state.backStack.size)
            assertEquals("news", state.currentEntry.screen.route)
            assertFalse(state.backStack.any { it.screen.route == "profile" }) // Profile should be gone
            assertFalse(state.backStack.any { it.screen.route == "settings" }) // Settings should be gone
            assertFalse(state.backStack.any { it.screen.route == "about" }) // About should be gone
        }

    @Test
    fun `test popUpTo with non-existent route does not remove anything`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val testDispatcher = StandardTestDispatcher(testScheduler)
            val store = createStore {
                module(createSimpleNavigationModule())
                coroutineContext(testDispatcher)
            }

            // Build backstack
            store.navigate("profile")
            advanceUntilIdle()
            store.navigate("settings")
            advanceUntilIdle()

            val sizeBefore = store.selectState<NavigationState>().first().backStack.size

            // PopUpTo non-existent route
            assertFailsWith<RouteNotFoundException> {
                store.navigate("about") {
                    popUpTo("nonexistent")
                }
            }
            advanceUntilIdle()

            val state = store.selectState<NavigationState>().first()

            assertEquals("settings", state.currentEntry.screen.route)
            assertTrue(state.backStack.any { it.screen.route == "profile" })
            assertTrue(state.backStack.any { it.screen.route == "settings" })
        }

    @Test
    fun `test clearBackStack creates single entry`() = runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val store = createStore {
            module(createSimpleNavigationModule())
            coroutineContext(testDispatcher)
        }

        // Build backstack
        store.navigate("profile")
        advanceUntilIdle()
        store.navigate("settings")
        advanceUntilIdle()
        store.navigate("about")
        advanceUntilIdle()

        // Clear backstack and navigate to news
        store.clearBackStack {
            navigate("content/news")
        }
        advanceUntilIdle()

        val state = store.selectState<NavigationState>().first()

        // Should have only one entry
        assertEquals(1, state.backStack.size)
        assertEquals("news", state.currentEntry.screen.route)
        assertEquals("content", state.currentEntry.graphId)
        assertFalse(state.canGoBack)
    }

    @Test
    fun `test replaceWith replaces current screen without changing backstack size`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val testDispatcher = StandardTestDispatcher(testScheduler)
            val store = createStore {
                module(createSimpleNavigationModule())
                coroutineContext(testDispatcher)
            }

            // Build backstack
            store.navigate("profile")
            advanceUntilIdle()
            store.navigate("settings")
            advanceUntilIdle()

            val sizeBefore = store.selectState<NavigationState>().first().backStack.size

            // Replace current screen
            store.replaceWith("about")
            advanceUntilIdle()

            val state = store.selectState<NavigationState>().first()

            // Size should remain the same
            assertEquals(sizeBefore, state.backStack.size)
            assertEquals("about", state.currentEntry.screen.route)

            // Settings should be replaced by about
            assertFalse(state.backStack.any { it.screen.route == "settings" })
            assertTrue(state.backStack.any { it.screen.route == "about" })
            assertTrue(state.backStack.any { it.screen.route == "profile" }) // Profile should still be there
        }

    @Test
    fun `test navigation with complex configuration`() = runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val store = createStore {
            module(createSimpleNavigationModule())
            coroutineContext(testDispatcher)
        }

        // Build complex backstack
        store.navigate("profile")
        advanceUntilIdle()
        store.navigate("content/news")
        advanceUntilIdle()
        store.navigate("settings")
        advanceUntilIdle()
        store.navigate("about")
        advanceUntilIdle()

        // Complex navigation: navigate to workspace, pop up to news (inclusive), 
        store.navigate("content/workspace") {
            popUpTo("news", inclusive = true)
        }
        advanceUntilIdle()

        val state = store.selectState<NavigationState>().first()

        assertEquals("workspace", state.currentEntry.screen.route)

        // Should have: [home, profile, workspace]
        assertEquals(3, state.backStack.size)
        assertTrue(state.backStack.any { it.screen.route == "home" })
        assertTrue(state.backStack.any { it.screen.route == "profile" })
        assertTrue(state.backStack.any { it.screen.route == "workspace" })

        // News, settings, and about should be gone
        assertFalse(state.backStack.any { it.screen.route == "news" })
        assertFalse(state.backStack.any { it.screen.route == "settings" })
        assertFalse(state.backStack.any { it.screen.route == "about" })
    }

    @Test
    fun `test navigation configuration validation`() = runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val store = createStore {
            module(createSimpleNavigationModule())
            coroutineContext(testDispatcher)
        }

        // Try to combine clearBackStack with popUpTo (should fail)
        val exception1 = assertFailsWith<IllegalStateException> {
            store.navigate("profile") {
                clearBackStack()
                popUpTo("home")
            }
        }
        println("${exception1.message}")
        assertTrue(exception1.message?.contains("clearBackstack") ?: false)

        // Try to combine clearBackStack with replaceWith (should fail)
        val exception2 = assertFailsWith<IllegalStateException> {
            store.navigate("profile") {
                clearBackStack()
                replaceWith("settings")
            }
        }
        assertTrue(exception2.message?.contains("clearBackstack") ?: false)
    }

    @Test
    fun `test backstack consistency after multiple operations`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val testDispatcher = StandardTestDispatcher(testScheduler)
            val store = createStore {
                module(createSimpleNavigationModule())
                coroutineContext(testDispatcher)
            }

            // Perform a series of operations
            store.navigate("profile")
            advanceUntilIdle()
            store.navigate("settings")
            advanceUntilIdle()
            store.navigateBack()
            advanceUntilIdle()
            store.navigate("about") {
                popUpTo("home")
            }
            advanceUntilIdle()
            store.replaceWith("content/news")
            advanceUntilIdle()

            val state = store.selectState<NavigationState>().first()

            // Verify invariants
            assertTrue(state.backStack.isNotEmpty())
            assertEquals(state.currentEntry, state.backStack.last()) // Current entry should always be last

            // Verify all entries have valid properties
            state.backStack.forEach { entry ->
                assertTrue(entry.screen.route.isNotEmpty())
                assertTrue(entry.graphId.isNotEmpty())
                assertTrue(entry.params != null) // Should never be null
            }

            // Verify canGoBack consistency
            assertEquals(state.backStack.size > 1, state.canGoBack)
        }

    @Test
    fun `test parameter handling in navigation operations`() = runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val store = createStore {
            module(createSimpleNavigationModule())
            coroutineContext(testDispatcher)
        }

        // Navigate with parameters
        store.navigate("profile", mapOf("userId" to "123"))
        advanceUntilIdle()
        store.navigate("settings", mapOf("theme" to "dark"))
        advanceUntilIdle()

        // PopUpTo should preserve parameters of remaining screens
        store.navigate("about", mapOf("source" to "menu")) {
            popUpTo("profile")
        }
        advanceUntilIdle()

        val state = store.selectState<NavigationState>().first()

        // Profile's parameters should be preserved
        val profileEntry = state.backStack.find { it.screen.route == "profile" }
        assertNotNull(profileEntry)
        assertEquals("123", profileEntry!!.params["userId"])

        // About's parameters should be set
        assertEquals("menu", state.currentEntry.params["source"])

        // Settings should be gone along with its parameters
        assertFalse(state.backStack.any { it.screen.route == "settings" })
    }

    @Test
    fun `test navigation back with empty backstack`() = runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val store = createStore {
            module(createSimpleNavigationModule())
            coroutineContext(testDispatcher)
        }

        // Clear backstack to have only one entry
        store.clearBackStack {
            navigate("profile")
        }
        advanceUntilIdle()

        val stateBefore = store.selectState<NavigationState>().first()
        assertEquals(1, stateBefore.backStack.size)
        assertFalse(stateBefore.canGoBack)

        // Try to navigate back - should do nothing
        store.navigateBack()
        advanceUntilIdle()

        val stateAfter = store.selectState<NavigationState>().first()
        assertEquals(1, stateAfter.backStack.size)
        assertEquals("profile", stateAfter.currentEntry.screen.route)
        assertEquals(stateBefore.currentEntry, stateAfter.currentEntry) // Should be unchanged
    }

    @Test
    fun `test graph context preservation in navigation operations`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val testDispatcher = StandardTestDispatcher(testScheduler)
            val store = createStore {
                module(createSimpleNavigationModule())
                coroutineContext(testDispatcher)
            }

            // Navigate between different graphs
            store.navigate("content/news") // content graph
            advanceUntilIdle()
            store.navigate("profile") // root graph
            advanceUntilIdle()
            store.navigate("content/workspace") // content graph
            advanceUntilIdle()

            // PopUpTo screen in different graph
            store.navigate("settings") {
                popUpTo("news") // Should find news in content graph
            }
            advanceUntilIdle()

            val state = store.selectState<NavigationState>().first()

            // Should have news (content) and settings (root)
            assertTrue(state.backStack.any { it.screen.route == "news" && it.graphId == "content" })
            assertEquals("settings", state.currentEntry.screen.route)
            assertEquals("root", state.currentEntry.graphId)

            // Should not have profile or workspace
            assertFalse(state.backStack.any { it.screen.route == "profile" })
            assertFalse(state.backStack.any { it.screen.route == "workspace" })
        }
}