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
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.DurationUnit
import kotlin.time.toDuration

@OptIn(ExperimentalCoroutinesApi::class)
class ConcurrentResetTest {

    private val lifecycleCreatedCount = mutableMapOf<String, Int>()
    private val removalHandlerCount = mutableMapOf<String, Int>()

    private fun createTrackingScreen(screenRoute: String) = object : Screen {
        override val route = screenRoute
        override val enterTransition = NavTransition.None
        override val exitTransition = NavTransition.None

        @Composable
        override fun Content(params: Params) {
            Text(screenRoute)
        }

        override suspend fun onLifecycleCreated(lifecycle: BackstackLifecycle) {
            lifecycleCreatedCount[screenRoute] = (lifecycleCreatedCount[screenRoute] ?: 0) + 1
            lifecycle.invokeOnRemoval {
                removalHandlerCount[screenRoute] = (removalHandlerCount[screenRoute] ?: 0) + 1
            }
        }
    }

    @Test
    fun `concurrent reset calls are handled safely`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val testDispatcher = StandardTestDispatcher(testScheduler)

            val homeScreen = createTrackingScreen("home")
            val profileScreen = createTrackingScreen("profile")

            val navModule = createNavigationModule {
                rootGraph {
                    startScreen(homeScreen)
                    screens(homeScreen, profileScreen)
                }
            }

            val store = createStore {
                module(navModule)
                coroutineContext(testDispatcher)
            }
            advanceUntilIdle()

            store.navigation { navigateTo("profile") }
            advanceUntilIdle()

            // Clear counts before rapid resets
            lifecycleCreatedCount.clear()
            removalHandlerCount.clear()

            val results = mutableListOf<Boolean>()
            launch { results.add(store.reset()) }
            launch { results.add(store.reset()) }
            launch { results.add(store.reset()) }
            advanceUntilIdle()

            assertEquals(
                1,
                results.count { it },
                "Exactly one reset should execute (return true)"
            )
            assertEquals(
                2,
                results.count { !it },
                "Two resets should be skipped (return false)"
            )

            // After full reset, state returns to initialState (home only)
            // Only home lifecycle is recreated; profile is no longer in backstack
            assertEquals(
                1,
                lifecycleCreatedCount["home"],
                "Home lifecycle should only be created once despite multiple reset calls"
            )
            assertEquals(
                null,
                lifecycleCreatedCount["profile"],
                "Profile lifecycle should not be recreated after full reset"
            )

            // Removal handlers should only run once
            assertEquals(
                1,
                removalHandlerCount["home"],
                "Home removal handler should only run once"
            )
            assertEquals(
                1,
                removalHandlerCount["profile"],
                "Profile removal handler should only run once"
            )
        }

    @Test
    fun `sequential resets work correctly`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val testDispatcher = StandardTestDispatcher(testScheduler)

            val homeScreen = createTrackingScreen("home")
            val profileScreen = createTrackingScreen("profile")

            val navModule = createNavigationModule {
                rootGraph {
                    startScreen(homeScreen)
                    screens(homeScreen, profileScreen)
                }
            }

            val store = createStore {
                module(navModule)
                coroutineContext(testDispatcher)
            }
            advanceUntilIdle()

            store.navigation { navigateTo("profile") }
            advanceUntilIdle()

            lifecycleCreatedCount.clear()
            removalHandlerCount.clear()
            val firstResult = store.reset()
            advanceUntilIdle()

            assertTrue(firstResult, "First reset should execute and return true")
            val firstResetCreated = lifecycleCreatedCount.values.sum()
            val firstResetRemoved = removalHandlerCount.values.sum()

            lifecycleCreatedCount.clear()
            removalHandlerCount.clear()
            val secondResult = store.reset()
            advanceUntilIdle()

            assertTrue(secondResult, "Second reset should execute and return true")
            val secondResetCreated = lifecycleCreatedCount.values.sum()
            val secondResetRemoved = removalHandlerCount.values.sum()

            // First reset: home + profile removed, home recreated
            // Second reset: home removed (only entry after full reset), home recreated
            assertEquals(1, secondResetCreated, "Second reset should recreate home lifecycle")
            assertEquals(1, secondResetRemoved, "Second reset should remove home lifecycle")
            assertTrue(firstResetCreated > 0, "Lifecycles should be created after first reset")
            assertTrue(firstResetRemoved > 0, "Removal handlers should run during first reset")
        }
}
