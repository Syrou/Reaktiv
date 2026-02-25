import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import io.github.syrou.reaktiv.core.ModuleAction
import io.github.syrou.reaktiv.core.ModuleLogic
import io.github.syrou.reaktiv.core.ModuleState
import io.github.syrou.reaktiv.core.ModuleWithLogic
import io.github.syrou.reaktiv.core.StoreAccessor
import io.github.syrou.reaktiv.core.createStore
import io.github.syrou.reaktiv.navigation.NavigationAction
import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.createNavigationModule
import io.github.syrou.reaktiv.navigation.definition.BackstackLifecycle
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.extension.navigateBack
import io.github.syrou.reaktiv.navigation.extension.navigation
import io.github.syrou.reaktiv.navigation.param.Params
import io.github.syrou.reaktiv.navigation.transition.NavTransition
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.DurationUnit
import kotlin.time.toDuration

@OptIn(ExperimentalCoroutinesApi::class)
class ResetFromActionHandlerTest {

    @Serializable
    data class ResetTriggerState(val shouldReset: Boolean = false) : ModuleState

    sealed class ResetTriggerAction(moduleTag: kotlin.reflect.KClass<*>) : ModuleAction(moduleTag) {
        data object TriggerReset : ResetTriggerAction(ResetTriggerModule::class)
    }

    class ResetTriggerLogic(
        private val storeAccessor: StoreAccessor,
        private val lifecycleEvents: MutableList<String>
    ) : ModuleLogic<ResetTriggerAction>() {

        init {
            startObservation()
        }

        private fun startObservation() {
            storeAccessor.launch {
                storeAccessor.selectState(NavigationState::class)
                    .map { it.lastNavigationAction }
                    .distinctUntilChanged()
                    .filterNotNull()
                    .collect { action ->
                        if (action is NavigationAction.Navigate) {
                            val route = action.entry.navigatable.route
                            if (route == "settings") {
                                lifecycleEvents.add("trigger:saw-settings")
                                lifecycleEvents.add("trigger:calling-reset")
                                storeAccessor.reset()
                                lifecycleEvents.add("trigger:reset-returned")
                            }
                        }
                    }
            }
        }
    }

    object ResetTriggerModule : ModuleWithLogic<ResetTriggerState, ResetTriggerAction, ResetTriggerLogic> {
        override val initialState = ResetTriggerState()
        override val reducer = { state: ResetTriggerState, _: ResetTriggerAction -> state }
        override val createLogic = { storeAccessor: StoreAccessor ->
            ResetTriggerLogic(storeAccessor, mutableListOf())
        }
    }

    class ResetTriggerModuleImpl(
        private val lifecycleEvents: MutableList<String>
    ) : ModuleWithLogic<ResetTriggerState, ResetTriggerAction, ResetTriggerLogic> {
        override val initialState = ResetTriggerState()
        override val reducer = { state: ResetTriggerState, _: ResetTriggerAction -> state }
        override val createLogic = { storeAccessor: StoreAccessor ->
            ResetTriggerLogic(storeAccessor, lifecycleEvents)
        }
    }

    private fun createTrackingScreen(
        screenRoute: String,
        lifecycleEvents: MutableList<String>
    ) = object : Screen {
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
    fun `reset from action handler allows lifecycle to work correctly`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val testDispatcher = StandardTestDispatcher(testScheduler)
            val lifecycleEvents = mutableListOf<String>()

            val homeScreen = createTrackingScreen("home", lifecycleEvents)
            val profileScreen = createTrackingScreen("profile", lifecycleEvents)
            val settingsScreen = createTrackingScreen("settings", lifecycleEvents)

            val triggerModule = ResetTriggerModuleImpl(lifecycleEvents)

            val navModule = createNavigationModule {
                rootGraph {
                    startScreen(homeScreen)
                    screens(homeScreen, profileScreen, settingsScreen)
                }
            }

            val store = createStore {
                module(navModule)
                module(triggerModule)
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
            store.navigation { navigateTo("settings") }
            advanceUntilIdle()

            assertEquals(
                listOf(
                    "settings:created",
                    "trigger:saw-settings",
                    "trigger:calling-reset",
                    "home:removed",
                    "settings:removed",
                    "trigger:reset-returned",
                    "home:created"
                ),
                lifecycleEvents,
                "Reset from action handler should fully reset store to initialState"
            )

            lifecycleEvents.clear()
            store.navigation { navigateTo("profile") }
            advanceUntilIdle()

            assertEquals(
                listOf("profile:created"),
                lifecycleEvents,
                "Lifecycle should work correctly after reset from action handler"
            )

            lifecycleEvents.clear()
            store.navigateBack()
            advanceUntilIdle()
            assertEquals(listOf("profile:removed"), lifecycleEvents)
        }
}
