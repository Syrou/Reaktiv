import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import io.github.syrou.reaktiv.core.createStore
import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.createNavigationModule
import io.github.syrou.reaktiv.navigation.definition.BackstackLifecycle
import io.github.syrou.reaktiv.navigation.definition.Screen
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
import kotlin.test.assertTrue
import kotlin.time.DurationUnit
import kotlin.time.toDuration

@OptIn(ExperimentalCoroutinesApi::class)
class ScreenLifecycleResetTest {

    private val lifecycleCreatedRoutes = mutableListOf<String>()
    private val removalHandlerRoutes = mutableListOf<String>()
    private val lifecycleCreatedCounts = mutableMapOf<String, Int>()

    private fun createLifecycleTrackingScreen(screenRoute: String) = object : Screen {
        override val route = screenRoute
        override val enterTransition = NavTransition.None
        override val exitTransition = NavTransition.None

        @Composable
        override fun Content(params: Params) {
            Text(screenRoute)
        }

        override suspend fun onLifecycleCreated(lifecycle: BackstackLifecycle) {
            lifecycleCreatedRoutes.add(screenRoute)
            lifecycleCreatedCounts[screenRoute] = (lifecycleCreatedCounts[screenRoute] ?: 0) + 1
            lifecycle.invokeOnRemoval {
                removalHandlerRoutes.add(screenRoute)
            }
        }
    }

    @Test
    fun `lifecycle events work correctly after store reset`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val testDispatcher = StandardTestDispatcher(testScheduler)

            val homeScreen = createLifecycleTrackingScreen("home")
            val profileScreen = createLifecycleTrackingScreen("profile")
            val settingsScreen = createLifecycleTrackingScreen("settings")

            val navModule = createNavigationModule {
                rootGraph {
                    startScreen(homeScreen)
                    screens(homeScreen, profileScreen, settingsScreen)
                }
            }

            val store = createStore {
                module(navModule)
                coroutineContext(testDispatcher)
            }
            advanceUntilIdle()

            // Verify initial lifecycle created for home screen
            assertTrue(
                lifecycleCreatedRoutes.contains("home"),
                "onLifecycleCreated should fire for initial screen"
            )

            // Navigate to profile
            store.navigation { navigateTo("profile") }
            advanceUntilIdle()

            assertTrue(
                lifecycleCreatedRoutes.contains("profile"),
                "onLifecycleCreated should fire for profile screen"
            )

            val preResetCreatedCount = lifecycleCreatedRoutes.size
            removalHandlerRoutes.clear()

            val resetExecuted = store.reset()
            advanceUntilIdle()

            assertTrue(resetExecuted, "Reset should execute and return true")

            // Verify removal handlers were called for pre-reset entries
            assertTrue(
                removalHandlerRoutes.contains("home"),
                "Removal handler should run for home screen on reset"
            )
            assertTrue(
                removalHandlerRoutes.contains("profile"),
                "Removal handler should run for profile screen on reset"
            )

            // After full reset, state returns to initialState — home lifecycle is recreated
            val postResetCreatedCount = lifecycleCreatedRoutes.size
            assertTrue(
                postResetCreatedCount > preResetCreatedCount,
                "onLifecycleCreated should fire again after reset for backstack entries"
            )

            // Navigate to settings after reset to prove lifecycle system is functional
            lifecycleCreatedRoutes.clear()
            store.navigation { navigateTo("settings") }
            advanceUntilIdle()

            assertTrue(
                lifecycleCreatedRoutes.contains("settings"),
                "onLifecycleCreated should fire for new screen after reset"
            )

            val state = store.selectState<NavigationState>().first()
            assertEquals("settings", state.currentEntry.route)
        }

    @Test
    fun `removal handlers are called for all backstack entries on reset`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val testDispatcher = StandardTestDispatcher(testScheduler)

            val homeScreen = createLifecycleTrackingScreen("home")
            val profileScreen = createLifecycleTrackingScreen("profile")
            val settingsScreen = createLifecycleTrackingScreen("settings")

            val navModule = createNavigationModule {
                rootGraph {
                    startScreen(homeScreen)
                    screens(homeScreen, profileScreen, settingsScreen)
                }
            }

            val store = createStore {
                module(navModule)
                coroutineContext(testDispatcher)
            }
            advanceUntilIdle()

            store.navigation { navigateTo("profile") }
            advanceUntilIdle()
            store.navigation { navigateTo("settings") }
            advanceUntilIdle()

            val state = store.selectState<NavigationState>().first()
            assertEquals(3, state.backStack.size)

            removalHandlerRoutes.clear()
            val resetExecuted = store.reset()
            advanceUntilIdle()

            assertTrue(resetExecuted, "Reset should execute and return true")

            // All three entries should have had their removal handlers called
            assertEquals(
                3,
                removalHandlerRoutes.size,
                "All backstack entries should have removal handlers called on reset"
            )
            assertTrue(removalHandlerRoutes.contains("home"))
            assertTrue(removalHandlerRoutes.contains("profile"))
            assertTrue(removalHandlerRoutes.contains("settings"))
        }

    @Test
    fun `no duplicate lifecycle events after reset due to overlapping observations`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val testDispatcher = StandardTestDispatcher(testScheduler)

            val homeScreen = createLifecycleTrackingScreen("home")
            val profileScreen = createLifecycleTrackingScreen("profile")
            val settingsScreen = createLifecycleTrackingScreen("settings")

            val navModule = createNavigationModule {
                rootGraph {
                    startScreen(homeScreen)
                    screens(homeScreen, profileScreen, settingsScreen)
                }
            }

            val store = createStore {
                module(navModule)
                coroutineContext(testDispatcher)
            }
            advanceUntilIdle()

            // Home should be created once
            assertEquals(1, lifecycleCreatedCounts["home"])

            // Navigate to profile
            store.navigation { navigateTo("profile") }
            advanceUntilIdle()

            // Profile should be created once
            assertEquals(1, lifecycleCreatedCounts["profile"])

            lifecycleCreatedCounts.clear()
            val resetExecuted = store.reset()
            advanceUntilIdle()

            assertTrue(resetExecuted, "Reset should execute and return true")

            // After full reset, state returns to initialState (home only).
            // Home lifecycle is recreated exactly once; profile is no longer in backstack.
            assertEquals(
                1,
                lifecycleCreatedCounts["home"],
                "Home lifecycle should be created exactly once after reset, not multiple times"
            )
            assertEquals(
                null,
                lifecycleCreatedCounts["profile"],
                "Profile lifecycle should not be recreated after full reset"
            )

            // Navigate to a new screen (settings) after reset to verify observation is working
            lifecycleCreatedCounts.clear()
            store.navigation { navigateTo("settings") }
            advanceUntilIdle()

            // Settings lifecycle should be created exactly once
            assertEquals(
                1,
                lifecycleCreatedCounts["settings"] ?: 0,
                "Navigating to new screen after reset should create lifecycle exactly once"
            )
        }
}
