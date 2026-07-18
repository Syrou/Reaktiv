import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import io.github.syrou.reaktiv.core.ModuleState
import io.github.syrou.reaktiv.core.createStore
import io.github.syrou.reaktiv.core.util.selectState
import io.github.syrou.reaktiv.core.util.selectLogic
import io.github.syrou.reaktiv.navigation.NavigationAction
import io.github.syrou.reaktiv.navigation.NavigationLogic
import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.createNavigationModule
import io.github.syrou.reaktiv.navigation.definition.BackstackLifecycle
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.extension.navigateBack
import io.github.syrou.reaktiv.navigation.extension.navigation
import io.github.syrou.reaktiv.navigation.param.Params
import io.github.syrou.reaktiv.navigation.transition.NavTransition
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.DurationUnit
import kotlin.time.toDuration

class ProjectionLifecycleTest {

    private val lifecycleEvents = mutableListOf<String>()

    private fun trackingScreen(screenRoute: String) = object : Screen {
        override val route = screenRoute
        override val enterTransition = NavTransition.None
        override val exitTransition = NavTransition.None

        override suspend fun onLifecycleCreated(lifecycle: BackstackLifecycle) {
            lifecycleEvents.add("$screenRoute:created")
            lifecycle.invokeOnRemoval {
                lifecycleEvents.add("$screenRoute:removed")
            }
        }

        @Composable
        override fun Content(params: Params) { Text(screenRoute) }
    }

    private val navStateKey = NavigationState::class.qualifiedName!!

    private fun TestScope.buildStore(): io.github.syrou.reaktiv.core.Store {
        val home = trackingScreen("proj-home")
        val profile = trackingScreen("proj-profile")
        val settings = trackingScreen("proj-settings")
        return createStore {
            module(createNavigationModule {
                rootGraph {
                    start(home)
                    screens(home, profile, settings)
                }
            })
            coroutineContext(StandardTestDispatcher(testScheduler))
        }
    }

    @Test
    fun `projected state change fires no lifecycle callbacks`() =
        runTest(timeout = 10.toDuration(DurationUnit.SECONDS)) {
            val store = buildStore()
            advanceUntilIdle()
            val homeState = store.selectState<NavigationState>().first()

            store.navigation { navigateTo("proj-profile") }
            advanceUntilIdle()
            lifecycleEvents.clear()

            store.asInternalOperations()!!.applyExternalStates(
                mapOf<String, ModuleState>(navStateKey to homeState)
            )
            advanceUntilIdle()

            assertEquals("proj-home", store.selectState<NavigationState>().first().currentEntry.route)
            assertTrue(lifecycleEvents.isEmpty(), "projection fired lifecycle: $lifecycleEvents")
        }

    @Test
    fun `local navigation after projection self-heals via lifecycle bookkeeping`() =
        runTest(timeout = 10.toDuration(DurationUnit.SECONDS)) {
            val store = buildStore()
            advanceUntilIdle()
            val homeState = store.selectState<NavigationState>().first()

            store.navigation { navigateTo("proj-profile") }
            advanceUntilIdle()

            store.asInternalOperations()!!.applyExternalStates(
                mapOf<String, ModuleState>(navStateKey to homeState)
            )
            advanceUntilIdle()
            lifecycleEvents.clear()

            store.navigation { navigateTo("proj-settings") }
            advanceUntilIdle()

            assertTrue("proj-settings:created" in lifecycleEvents)
            assertTrue("proj-profile:removed" in lifecycleEvents)
        }

    @Test
    fun `adoptCurrentBackstack initializes lifecycles for projected entries`() =
        runTest(timeout = 10.toDuration(DurationUnit.SECONDS)) {
            val store = buildStore()
            advanceUntilIdle()

            store.navigation { navigateTo("proj-profile") }
            advanceUntilIdle()
            val twoDeepState = store.selectState<NavigationState>().first()

            store.navigateBack()
            advanceUntilIdle()
            lifecycleEvents.clear()

            store.asInternalOperations()!!.applyExternalStates(
                mapOf<String, ModuleState>(navStateKey to twoDeepState)
            )
            advanceUntilIdle()
            assertTrue(lifecycleEvents.isEmpty(), "projection fired lifecycle: $lifecycleEvents")

            store.selectLogic<NavigationLogic>().adoptCurrentBackstack()
            advanceUntilIdle()

            assertEquals(listOf("proj-profile:created"), lifecycleEvents)
        }

    @Test
    fun `directly dispatched navigation action still fires lifecycle`() =
        runTest(timeout = 10.toDuration(DurationUnit.SECONDS)) {
            val store = buildStore()
            advanceUntilIdle()

            store.navigation { navigateTo("proj-profile") }
            advanceUntilIdle()
            lifecycleEvents.clear()

            store.dispatch(NavigationAction.Back)
            advanceUntilIdle()

            assertEquals(listOf("proj-profile:removed"), lifecycleEvents)
        }
}
