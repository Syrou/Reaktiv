import io.github.syrou.reaktiv.core.createStore
import io.github.syrou.reaktiv.core.util.ReaktivDebug
import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.createNavigationModule
import io.github.syrou.reaktiv.navigation.exception.RouteNotFoundException
import io.github.syrou.reaktiv.navigation.extension.clearBackStack
import io.github.syrou.reaktiv.navigation.extension.clearCurrentScreenParam
import io.github.syrou.reaktiv.navigation.extension.clearCurrentScreenParams
import io.github.syrou.reaktiv.navigation.extension.clearScreenParam
import io.github.syrou.reaktiv.navigation.extension.clearScreenParams
import io.github.syrou.reaktiv.navigation.extension.navigate
import io.github.syrou.reaktiv.navigation.extension.navigateBack
import io.github.syrou.reaktiv.navigation.extension.navigation
import io.github.syrou.reaktiv.navigation.extension.popUpTo
import io.github.syrou.reaktiv.navigation.extension.replaceWith
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.DurationUnit
import kotlin.time.toDuration


@OptIn(ExperimentalCoroutinesApi::class)
class LegacyNavigationExtensionsTest {

    @Serializable
    data class TestUser(val id: String, val name: String, val email: String)

    @Serializable
    data class TestPreferences(val theme: String, val language: String, val notifications: Boolean)

    private fun createTestNavigationModule() = createNavigationModule {
        rootGraph {
            startScreen(SplashScreen)
            screens(HomeScreen, ProfileScreen, SettingsScreen)

            graph("content") {
                startScreen(NewsScreen)
                screens(WorkspaceOverviewScreen)

                graph("workspace") {
                    startScreen(ProjectOverviewScreen)
                    screens(ProjectTaskScreen)
                }
            }
        }
    }

    @Test
    fun `test basic legacy navigate method`() = runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val store = createStore {
            module(createTestNavigationModule())
            coroutineContext(testDispatcher)
        }

        // Basic navigation with parameters
        store.navigate("profile", mapOf("userId" to "123", "source" to "menu"))
        advanceUntilIdle()

        val state = store.selectState<NavigationState>().first()
        assertEquals("profile", state.currentEntry.screen.route)
        assertEquals("root", state.currentEntry.graphId)
        assertEquals("123", state.currentEntry.params["userId"])
        assertEquals("menu", state.currentEntry.params["source"])
        assertEquals(2, state.backStack.size) // [splash, profile]
    }

    @Test
    fun `test legacy navigate with popUpTo configuration`() = runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val store = createStore {
            module(createTestNavigationModule())
            coroutineContext(testDispatcher)
        }

        // Build backstack
        store.navigate("profile")
        advanceUntilIdle()
        store.navigate("settings")
        advanceUntilIdle()
        store.navigate("content/news")
        advanceUntilIdle()

        // Navigate with popUpTo
        store.navigate("home-overview", mapOf("source" to "navigation")) {
            popUpTo("profile", inclusive = false)
        }
        advanceUntilIdle()

        val state = store.selectState<NavigationState>().first()
        assertEquals("home-overview", state.currentEntry.screen.route)
        assertEquals("navigation", state.currentEntry.params["source"])
        
        // Should have: [splash, profile, home]
        assertEquals(3, state.backStack.size)
        assertTrue(state.backStack.any { it.screen.route == "profile" })
        assertFalse(state.backStack.any { it.screen.route == "settings" })
        assertFalse(state.backStack.any { it.screen.route == "news" })
    }

    @Test
    fun `test legacy navigate with forwardParams`() = runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val store = createStore {
            module(createTestNavigationModule())
            coroutineContext(testDispatcher)
        }

        // Navigate with initial parameters
        store.navigate("profile", mapOf("sessionId" to "abc123", "theme" to "dark"))
        advanceUntilIdle()

        // Navigate with forwardParams - should merge parameters
        store.navigate("settings", mapOf("source" to "profile", "section" to "general")) {
            forwardParams()
        }
        advanceUntilIdle()

        val state = store.selectState<NavigationState>().first()
        assertEquals("settings", state.currentEntry.screen.route)
        
        // Should have both old and new parameters
        assertEquals("abc123", state.currentEntry.params["sessionId"]) // Forwarded
        assertEquals("dark", state.currentEntry.params["theme"]) // Forwarded
        assertEquals("profile", state.currentEntry.params["source"]) // New
        assertEquals("general", state.currentEntry.params["section"]) // New
    }

    @Test
    fun `test parameter survival through navigation`() = runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val store = createStore {
            module(createTestNavigationModule())
            coroutineContext(testDispatcher)
        }

        // Navigate with persistent parameters
        val persistentParams = mapOf(
            "sessionId" to "session-456",
            "userId" to "user-789",
            "appVersion" to "1.2.3"
        )
        
        store.navigate("profile", persistentParams)
        advanceUntilIdle()

        // Navigate away
        store.navigate("settings", mapOf("temporary" to "value"))
        advanceUntilIdle()

        // Navigate back
        store.navigateBack()
        advanceUntilIdle()

        val state = store.selectState<NavigationState>().first()
        assertEquals("profile", state.currentEntry.screen.route)
        
        // Original parameters should still be there
        assertEquals("session-456", state.currentEntry.params["sessionId"])
        assertEquals("user-789", state.currentEntry.params["userId"])
        assertEquals("1.2.3", state.currentEntry.params["appVersion"])
        assertFalse(state.currentEntry.params.containsKey("temporary"))
    }

    @Test
    fun `test legacy replaceWith method`() = runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val store = createStore {
            module(createTestNavigationModule())
            coroutineContext(testDispatcher)
        }

        // Build initial backstack
        store.navigate("profile")
        advanceUntilIdle()
        store.navigate("settings")
        advanceUntilIdle()

        val sizeBefore = store.selectState<NavigationState>().first().backStack.size

        // Replace current screen
        store.replaceWith("home-overview", mapOf("source" to "replace", "timestamp" to 1234567890L))
        advanceUntilIdle()

        val state = store.selectState<NavigationState>().first()
        assertEquals("home-overview", state.currentEntry.screen.route)
        assertEquals("replace", state.currentEntry.params["source"])
        assertEquals(1234567890L, state.currentEntry.params["timestamp"])
        assertEquals(sizeBefore, state.backStack.size) // Size unchanged
        
        // Settings should be gone, replaced by home
        assertFalse(state.backStack.any { it.screen.route == "settings" })
        assertTrue(state.backStack.any { it.screen.route == "home-overview" })
        assertTrue(state.backStack.any { it.screen.route == "profile" })
    }

    @Test
    fun `test legacy clearBackStack method`() = runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val store = createStore {
            module(createTestNavigationModule())
            coroutineContext(testDispatcher)
        }

        // Build complex backstack
        store.navigate("profile")
        advanceUntilIdle()
        store.navigate("settings")
        advanceUntilIdle()
        store.navigate("content/news")
        advanceUntilIdle()

        // Clear backstack and navigate to new root
        store.clearBackStack {
            navigate("home-overview", mapOf("isRoot" to true, "cleared" to true))
        }
        advanceUntilIdle()

        val state = store.selectState<NavigationState>().first()
        assertEquals("home-overview", state.currentEntry.screen.route)
        assertEquals(true, state.currentEntry.params["isRoot"])
        assertEquals(true, state.currentEntry.params["cleared"])
        assertEquals(1, state.backStack.size) // Only current entry
        assertFalse(state.canGoBack)
    }

    @Test
    fun `test legacy popUpTo method`() = runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
        ReaktivDebug.enable()
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val store = createStore {
            module(createTestNavigationModule())
            coroutineContext(testDispatcher)
        }

        // Build backstack
        store.navigate("profile", mapOf("keep" to "me"))
        advanceUntilIdle()
        store.navigate("settings")
        advanceUntilIdle()
        store.navigate("content/news")
        advanceUntilIdle()
        store.navigate("content/workspace")
        advanceUntilIdle()

        val navigationState = store.selectStateNonSuspend<NavigationState>().first()
        println("TEST: navigationState: $navigationState")
        // PopUpTo profile (inclusive)
        store.popUpTo("profile", inclusive = true)
        advanceUntilIdle()

        val state = store.selectState<NavigationState>().first()
        assertEquals("splash", state.currentEntry.screen.route) // Back to what was before profile
        assertEquals(1, state.backStack.size) // Only splash remains

        // Profile and everything after should be gone
        assertFalse(state.backStack.any { it.screen.route == "profile" })
        assertFalse(state.backStack.any { it.screen.route == "settings" })
        assertFalse(state.backStack.any { it.screen.route == "news" })
        assertFalse(state.backStack.any { it.screen.route == "workspace" })
    }

    @Test
    fun `test legacy navigateBack method`() = runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val store = createStore {
            module(createTestNavigationModule())
            coroutineContext(testDispatcher)
        }

        // Build backstack
        store.navigate("profile", mapOf("step" to "1"))
        advanceUntilIdle()
        store.navigate("settings", mapOf("step" to "2"))
        advanceUntilIdle()
        store.navigate("home-overview", mapOf("step" to "3"))
        advanceUntilIdle()

        // Should have 4 entries: [splash, profile, settings, home]
        assertEquals(4, store.selectState<NavigationState>().first().backStack.size)

        // Navigate back
        store.navigateBack()
        advanceUntilIdle()

        var state = store.selectState<NavigationState>().first()
        assertEquals("settings", state.currentEntry.screen.route)
        assertEquals("2", state.currentEntry.params["step"])
        assertEquals(3, state.backStack.size)

        // Navigate back again
        store.navigateBack()
        advanceUntilIdle()

        state = store.selectState<NavigationState>().first()
        assertEquals("profile", state.currentEntry.screen.route)
        assertEquals("1", state.currentEntry.params["step"])
        assertEquals(2, state.backStack.size)
    }

    @Test
    fun `test legacy parameter clearing methods`() = runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val store = createStore {
            module(createTestNavigationModule())
            coroutineContext(testDispatcher)
        }

        // Navigate with parameters to clear later
        store.navigate("profile", mapOf(
            "sessionId" to "session-123",
            "tempFlag" to "temporary",
            "userId" to "user-456",
            "debugMode" to true
        ))
        advanceUntilIdle()

        // Test clearing a single parameter
        store.clearCurrentScreenParam("tempFlag")
        advanceUntilIdle()

        var state = store.selectState<NavigationState>().first()
        assertFalse(state.currentEntry.params.containsKey("tempFlag"))
        assertTrue(state.currentEntry.params.containsKey("sessionId"))
        assertTrue(state.currentEntry.params.containsKey("userId"))
        assertTrue(state.currentEntry.params.containsKey("debugMode"))

        // Test clearing all parameters
        store.clearCurrentScreenParams()
        advanceUntilIdle()

        state = store.selectState<NavigationState>().first()
        assertTrue(state.currentEntry.params.isEmpty())
    }

    @Test
    fun `test legacy parameter clearing for specific routes`() = runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val store = createStore {
            module(createTestNavigationModule())
            coroutineContext(testDispatcher)
        }

        // Build backstack with parameters
        store.navigate("profile", mapOf("profileData" to "keep", "tempData" to "remove"))
        advanceUntilIdle()
        store.navigate("settings", mapOf("settingsData" to "keep", "tempData" to "remove"))
        advanceUntilIdle()

        // Clear specific parameter from profile route
        store.clearScreenParam("profile", "tempData")
        advanceUntilIdle()

        var state = store.selectState<NavigationState>().first()
        
        // Current screen (settings) should be unchanged
        assertEquals("settings", state.currentEntry.screen.route)
        assertTrue(state.currentEntry.params.containsKey("tempData"))
        
        // Profile in backstack should have tempData removed
        val profileEntry = state.backStack.find { it.screen.route == "profile" }
        assertTrue(profileEntry != null)
        assertFalse(profileEntry!!.params.containsKey("tempData"))
        assertTrue(profileEntry.params.containsKey("profileData"))

        // Clear all parameters from settings route
        store.clearScreenParams("settings")
        advanceUntilIdle()

        state = store.selectState<NavigationState>().first()
        assertTrue(state.currentEntry.params.isEmpty()) // Settings params cleared
        
        // Profile should still have its remaining parameter
        val profileEntryAfter = state.backStack.find { it.screen.route == "profile" }
        assertTrue(profileEntryAfter != null)
        assertTrue(profileEntryAfter!!.params.containsKey("profileData"))
    }

    @Test
    fun `test legacy error handling`() = runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val store = createStore {
            module(createTestNavigationModule())
            coroutineContext(testDispatcher)
        }

        // Test navigation to non-existent route
        assertFailsWith<RouteNotFoundException> {
            store.navigate("nonexistent")
            advanceUntilIdle()
        }

        // Test popUpTo non-existent route
        store.navigate("profile")
        advanceUntilIdle()
        
        assertFailsWith<RouteNotFoundException> {
            store.popUpTo("nonexistent")
            advanceUntilIdle()
        }
    }

    @Test
    fun `test legacy methods work alongside new unified builder`() = runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val store = createStore {
            module(createTestNavigationModule())
            coroutineContext(testDispatcher)
        }

        // Use legacy method
        store.navigate("profile", mapOf("legacy" to true))
        advanceUntilIdle()

        // Use new unified builder
        store.navigation {
            navigateTo<SettingsScreen>()
            putString("unified", "true")
            putBoolean("mixed", true)
        }
        advanceUntilIdle()

        // Use legacy method again
        store.navigateBack()
        advanceUntilIdle()

        val state = store.selectState<NavigationState>().first()
        assertEquals("profile", state.currentEntry.screen.route)
        assertEquals(true, state.currentEntry.params["legacy"])
        
        // Both systems should work together seamlessly
        assertEquals(2, state.backStack.size) // [splash, profile]
    }
}