import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import io.github.syrou.reaktiv.core.createStore
import io.github.syrou.reaktiv.navigation.createNavigationModule
import io.github.syrou.reaktiv.navigation.definition.BackstackLifecycle
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.extension.navigation
import io.github.syrou.reaktiv.navigation.param.Params
import io.github.syrou.reaktiv.navigation.transition.NavTransition
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.DurationUnit
import kotlin.time.toDuration

@OptIn(ExperimentalCoroutinesApi::class)
class LifecycleScreenAlreadyInBackstackTest {

    private val lifecycleEvents = mutableListOf<String>()

    private fun createTrackingScreen(screenRoute: String) = object : Screen {
        override val route = screenRoute
        override val enterTransition = NavTransition.None
        override val exitTransition = NavTransition.None
        override val requiresAuth = false

        @Composable
        override fun Content(params: Params) {
            Text(screenRoute)
        }

        override suspend fun onLifecycleCreated(lifecycle: BackstackLifecycle) {
            println("$screenRoute: onLifecycleCreated called")
            lifecycleEvents.add("$screenRoute:created")
            lifecycle.invokeOnRemoval {
                println("$screenRoute: invokeOnRemoval called")
                lifecycleEvents.add("$screenRoute:removed")
            }
        }
    }

    @Test
    fun `screen already in backstack gets new lifecycle after reset`() =
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

            lifecycleEvents.clear()

            store.navigation { navigateTo("profile") }
            advanceUntilIdle()
            assertEquals(listOf("profile:created"), lifecycleEvents)

            lifecycleEvents.clear()
            println("=== CALLING RESET ===")
            store.reset()
            advanceUntilIdle()
            println("=== RESET COMPLETE ===")
            println("Events after reset: $lifecycleEvents")

            assertEquals(
                3,
                lifecycleEvents.size,
                "Should have removal for both screens and creation for home only (full reset to initialState)"
            )
            assertEquals("home:removed", lifecycleEvents[0])
            assertEquals("profile:removed", lifecycleEvents[1])
            assertEquals("home:created", lifecycleEvents[2])
        }
}
