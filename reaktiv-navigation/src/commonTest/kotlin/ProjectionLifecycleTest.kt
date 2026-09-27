import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import io.github.syrou.reaktiv.core.ExternalStatePolicy
import io.github.syrou.reaktiv.core.HydrateSource
import io.github.syrou.reaktiv.core.ModuleState
import io.github.syrou.reaktiv.core.createStore
import io.github.syrou.reaktiv.core.persistance.PersistenceStrategy
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

    private class InMemoryPersistence : PersistenceStrategy {
        var saved: String? = null
        override suspend fun saveState(serializedState: String) {
            saved = serializedState
        }
        override suspend fun loadState(): String? = saved
        override suspend fun hasPersistedState(): Boolean = saved != null
    }

    private fun TestScope.buildStore(persistence: PersistenceStrategy? = null): io.github.syrou.reaktiv.core.Store {
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
            externalState(ExternalStatePolicy.Allow)
            persistence?.let { persistenceManager(it) }
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

            store.externalState()!!.hydrate(
                mapOf<String, ModuleState>(navStateKey to homeState),
                HydrateSource.Replication
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

            store.externalState()!!.hydrate(
                mapOf<String, ModuleState>(navStateKey to homeState),
                HydrateSource.Replication
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

            store.externalState()!!.hydrate(
                mapOf<String, ModuleState>(navStateKey to twoDeepState),
                HydrateSource.Replication
            )
            advanceUntilIdle()
            assertTrue(lifecycleEvents.isEmpty(), "projection fired lifecycle: $lifecycleEvents")

            @Suppress("DEPRECATION")
            store.selectLogic<NavigationLogic>().adoptCurrentBackstack()
            advanceUntilIdle()

            assertEquals(listOf("proj-profile:created"), lifecycleEvents)
        }

    @Test
    fun `restoring persisted navigation starts the lifecycle of the restored entries`() =
        runTest(timeout = 10.toDuration(DurationUnit.SECONDS)) {
            val store = buildStore(InMemoryPersistence())
            advanceUntilIdle()
            store.navigation { navigateTo("proj-profile") }
            advanceUntilIdle()
            store.saveState(store.getAllStates())
            store.navigateBack()
            advanceUntilIdle()
            lifecycleEvents.clear()

            store.loadState()
            advanceUntilIdle()

            assertEquals("proj-profile", store.selectState<NavigationState>().first().currentEntry.route)
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

            store.dispatch(NavigationAction.Back())
            advanceUntilIdle()

            assertEquals(listOf("proj-profile:removed"), lifecycleEvents)
        }
}
