import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import io.github.syrou.reaktiv.core.createStore
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
        override fun Content(params: Params) {
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
            store.navigation { navigateTo("profile") }
            advanceUntilIdle()
            store.navigation { navigateTo("settings") }
            advanceUntilIdle()
            store.navigation { navigateTo("about") }
            advanceUntilIdle()
            var state = store.selectState<NavigationState>().first()
            assertEquals(4, state.backStack.size)
            store.navigation {
                popUpTo("profile", inclusive = false)
                navigateTo("content/news")
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
            store.navigation { navigateTo("profile") }
            advanceUntilIdle()
            store.navigation { navigateTo("settings") }
            advanceUntilIdle()
            store.navigation { navigateTo("about") }
            advanceUntilIdle()

            // PopUpTo profile (inclusive) and navigate to news
            store.navigation {
                popUpTo("profile", inclusive = true)
                navigateTo("content/news")
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
            store.navigation { navigateTo("profile") }
            advanceUntilIdle()
            store.navigation { navigateTo("settings") }
            advanceUntilIdle()

            val sizeBefore = store.selectState<NavigationState>().first().backStack.size

            // PopUpTo non-existent route
            assertFailsWith<RouteNotFoundException> {
                store.navigation {
                    popUpTo("nonexistent")
                    navigateTo("about")
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
        store.navigation { navigateTo("profile") }
        advanceUntilIdle()
        store.navigation { navigateTo("settings") }
        advanceUntilIdle()
        store.navigation { navigateTo("about") }
        advanceUntilIdle()

        // Clear backstack and navigate to news
        store.navigation {
            clearBackStack()
            navigateTo("content/news")
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
    fun `test navigateTo with replaceCurrent replaces current screen without changing backstack size`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val testDispatcher = StandardTestDispatcher(testScheduler)
            val store = createStore {
                module(createSimpleNavigationModule())
                coroutineContext(testDispatcher)
            }

            // Build backstack
            store.navigation { navigateTo("profile") }
            advanceUntilIdle()
            store.navigation { navigateTo("settings") }
            advanceUntilIdle()

            val sizeBefore = store.selectState<NavigationState>().first().backStack.size

            // Replace current screen
            store.navigation { navigateTo("about", replaceCurrent = true) }
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
        store.navigation { navigateTo("profile") }
        advanceUntilIdle()
        store.navigation { navigateTo("content/news") }
        advanceUntilIdle()
        store.navigation { navigateTo("settings") }
        advanceUntilIdle()
        store.navigation { navigateTo("about") }
        advanceUntilIdle()

        // Complex navigation: navigate to workspace, pop up to news (inclusive), 
        store.navigation {
            popUpTo("news", inclusive = true)
            navigateTo("content/workspace")
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
        val exception1 = assertFailsWith<IllegalArgumentException> {
            store.navigation {
                clearBackStack()
                popUpTo("home")
                navigateTo("profile")
            }
        }
        println("${exception1.message}")
        assertTrue(exception1.message?.contains("clearBackStack") ?: false)

        // Try to combine clearBackStack with replaceCurrent (should fail)
        val exception2 = assertFailsWith<IllegalArgumentException> {
            store.navigation {
                clearBackStack()
                navigateTo("settings", replaceCurrent = true)
                navigateTo("profile")
            }
        }
        assertTrue(exception2.message?.contains("replaceCurrent") ?: false)
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
            store.navigation { navigateTo("profile") }
            advanceUntilIdle()
            store.navigation { navigateTo("settings") }
            advanceUntilIdle()
            store.navigateBack()
            advanceUntilIdle()
            store.navigation {
                popUpTo("home")
                navigateTo("about")
            }
            advanceUntilIdle()
            store.navigation { navigateTo("content/news", replaceCurrent = true) }
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
        store.navigation {
            navigateTo("profile"){
                putString("userId", "123")
            }
        }
        advanceUntilIdle()
        store.navigation {
            navigateTo("settings"){
                putString("theme", "dark")
            }
        }
        advanceUntilIdle()

        // PopUpTo should preserve parameters of remaining screens
        store.navigation {
            navigateTo("about"){
                putString("source", "menu")
            }
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
        store.navigation {
            clearBackStack()
            navigateTo("profile")
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
            store.navigation {
                navigateTo("content/news")
            }
            advanceUntilIdle()
            store.navigation {
                navigateTo("profile")
            }
            advanceUntilIdle()
            store.navigation {
                navigateTo("content/workspace")
            }
            advanceUntilIdle()

            // PopUpTo screen in different graph
            store.navigation {
                navigateTo("settings")
                popUpTo("news")
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

    @Test
    fun `test standalone popUpTo navigates to target screen`() = runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val store = createStore {
            module(createSimpleNavigationModule())
            coroutineContext(testDispatcher)
        }

        // Build backstack: home -> profile -> settings -> about
        store.navigation { navigateTo("profile") }
        advanceUntilIdle()
        store.navigation { navigateTo("settings") }
        advanceUntilIdle()
        store.navigation { navigateTo("about") }
        advanceUntilIdle()

        var state = store.selectState<NavigationState>().first()
        assertEquals(4, state.backStack.size)
        assertEquals("about", state.currentEntry.screen.route)

        // Test standalone popUpTo - should navigate back to profile
        store.navigation {
            popUpTo("profile")
        }
        advanceUntilIdle()

        state = store.selectState<NavigationState>().first()

        // Should navigate to profile and remove everything after it
        assertEquals("profile", state.currentEntry.screen.route)
        assertEquals(2, state.backStack.size) // [home, profile]
        assertTrue(state.backStack.any { it.screen.route == "home" })
        assertTrue(state.backStack.any { it.screen.route == "profile" })
        assertFalse(state.backStack.any { it.screen.route == "settings" })
        assertFalse(state.backStack.any { it.screen.route == "about" })
    }

    @Test
    fun `test combined popUpTo with navigateTo still works as before`() = runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val store = createStore {
            module(createSimpleNavigationModule())
            coroutineContext(testDispatcher)
        }

        // Build backstack: home -> profile -> settings -> about
        store.navigation { navigateTo("profile") }
        advanceUntilIdle()
        store.navigation { navigateTo("settings") }
        advanceUntilIdle()
        store.navigation { navigateTo("about") }
        advanceUntilIdle()

        var state = store.selectState<NavigationState>().first()
        assertEquals(4, state.backStack.size)
        assertEquals("about", state.currentEntry.screen.route)

        // Test combined popUpTo + navigateTo - should pop to profile then navigate to content/news
        store.navigation {
            popUpTo("profile")
            navigateTo("content/news")
        }
        advanceUntilIdle()

        state = store.selectState<NavigationState>().first()

        // Should navigate to news, not profile, and backstack should include popped-to state
        assertEquals("news", state.currentEntry.screen.route)
        assertEquals("content", state.currentEntry.graphId)
        assertEquals(3, state.backStack.size) // [home, profile, news]
        assertTrue(state.backStack.any { it.screen.route == "home" })
        assertTrue(state.backStack.any { it.screen.route == "profile" })
        assertTrue(state.backStack.any { it.screen.route == "news" })
        assertFalse(state.backStack.any { it.screen.route == "settings" })
        assertFalse(state.backStack.any { it.screen.route == "about" })
    }

    @Test
    fun `test standalone popUpTo with inclusive removes target screen`() = runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val store = createStore {
            module(createSimpleNavigationModule())
            coroutineContext(testDispatcher)
        }

        // Build backstack: home -> profile -> settings -> about
        store.navigation { navigateTo("profile") }
        advanceUntilIdle()
        store.navigation { navigateTo("settings") }
        advanceUntilIdle()
        store.navigation { navigateTo("about") }
        advanceUntilIdle()

        // Test standalone popUpTo with inclusive=true - should navigate to home (before profile)
        store.navigation {
            popUpTo("profile", inclusive = true)
        }
        advanceUntilIdle()

        val state = store.selectState<NavigationState>().first()

        // Should navigate to home and remove profile and everything after it
        assertEquals("home", state.currentEntry.screen.route)
        assertEquals(1, state.backStack.size) // [home]
        assertTrue(state.backStack.any { it.screen.route == "home" })
        assertFalse(state.backStack.any { it.screen.route == "profile" })
        assertFalse(state.backStack.any { it.screen.route == "settings" })
        assertFalse(state.backStack.any { it.screen.route == "about" })
    }
}