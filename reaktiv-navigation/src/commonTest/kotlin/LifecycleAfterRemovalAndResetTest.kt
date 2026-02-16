import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import io.github.syrou.reaktiv.core.createStore
import io.github.syrou.reaktiv.navigation.createNavigationModule
import io.github.syrou.reaktiv.navigation.definition.BackstackLifecycle
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.extension.navigateBack
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
class LifecycleAfterRemovalAndResetTest {

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
            lifecycleEvents.add("$screenRoute:created")
            lifecycle.invokeOnRemoval {
                lifecycleEvents.add("$screenRoute:removed")
            }
        }
    }

    @Test
    fun `lifecycle triggers after screen removal then reset then re-navigation`() =
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
            store.navigateBack()
            advanceUntilIdle()
            assertEquals(listOf("profile:removed"), lifecycleEvents)

            lifecycleEvents.clear()
            store.reset()
            advanceUntilIdle()
            assertEquals(listOf("home:removed", "home:created"), lifecycleEvents)

            lifecycleEvents.clear()
            store.navigation { navigateTo("profile") }
            advanceUntilIdle()
            assertEquals(
                listOf("profile:created"),
                lifecycleEvents,
                "onLifecycleCreated should trigger when navigating to profile after reset"
            )
        }
}
