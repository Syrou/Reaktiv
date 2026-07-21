import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import io.github.syrou.reaktiv.core.ExperimentalReaktivApi
import io.github.syrou.reaktiv.core.Module
import io.github.syrou.reaktiv.core.ModuleAction
import io.github.syrou.reaktiv.core.ModuleLogic
import io.github.syrou.reaktiv.core.ModuleState
import io.github.syrou.reaktiv.core.StoreAccessor
import io.github.syrou.reaktiv.core.createStore
import io.github.syrou.reaktiv.core.util.selectLogic
import io.github.syrou.reaktiv.core.util.selectState
import io.github.syrou.reaktiv.navigation.NavigationLogic
import io.github.syrou.reaktiv.navigation.NavigationOutcome
import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.createNavigationModule
import io.github.syrou.reaktiv.navigation.definition.LoadingModal
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.model.GuardResult
import io.github.syrou.reaktiv.navigation.param.Params
import io.github.syrou.reaktiv.navigation.transition.NavTransition
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.DurationUnit
import kotlin.time.toDuration

@Serializable
data class PreGateState(val ignored: Boolean = false) : ModuleState

sealed class PreGateAction : ModuleAction(PreGateModule::class) {
    data object Noop : PreGateAction()
}

/**
 * Stands in for `ToolingLogic`, which gates the store from its own constructor when a client
 * is configured to start as a follower. One-shot, like the real thing: logic is rebuilt on
 * every reset, and a standing gate would re-freeze the store the moment it recovers.
 */
@OptIn(ExperimentalReaktivApi::class)
class PreGateLogic(storeAccessor: StoreAccessor) : ModuleLogic() {
    init {
        if (PreGateModule.gateOnConstruction) {
            PreGateModule.gateOnConstruction = false
            storeAccessor.asInternalOperations()?.markExternallyDriven()
        }
    }
}

object PreGateModule : Module<PreGateState, PreGateAction> {
    var gateOnConstruction: Boolean = true

    override val initialState = PreGateState()
    override val reducer: (PreGateState, PreGateAction) -> PreGateState = { state, _ -> state }
    override val createLogic: (StoreAccessor) -> ModuleLogic = { PreGateLogic(it) }
}

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalReaktivApi::class)
class FollowerBootstrapGateTest {

    @BeforeTest
    fun resetPreGate() {
        PreGateModule.gateOnConstruction = true
    }

    private fun screen(route: String) = object : Screen {
        override val route = route
        override val enterTransition = NavTransition.None
        override val exitTransition = NavTransition.None

        @Composable
        override fun Content(params: Params) { Text(route) }
    }

    private fun loadingModal(route: String) = object : LoadingModal {
        override val route = route
        override val enterTransition = NavTransition.None
        override val exitTransition = NavTransition.None

        @Composable
        override fun Content(params: Params) { Text(route) }
    }

    private val homeScreen = screen("home")
    private val loginScreen = screen("login")
    private val overlay = loadingModal("loading")

    @Test
    fun `entering external control releases a bootstrap stuck on its entry selector`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val entryGate = CompletableDeferred<Screen>()
            val store = createStore {
                module(createNavigationModule {
                    loadingModal(overlay)
                    rootGraph {
                        start(route = { entryGate.await() })
                        screens(homeScreen, loginScreen)
                    }
                })
                coroutineContext(StandardTestDispatcher(testScheduler))
            }
            advanceUntilIdle()

            assertTrue(
                store.selectState<NavigationState>().first().isBootstrapping,
                "Bootstrap must still be pending while the entry selector suspends"
            )

            store.beginExternalControl()
            advanceUntilIdle()

            val state = store.selectState<NavigationState>().first()
            assertFalse(state.isBootstrapping, "Follower must not stay stuck behind its own bootstrap")
            assertFalse(state.isEvaluatingNavigation, "Evaluation overlay must be cleared")
            assertFalse(entryGate.isCompleted, "Entry selector must never have been resolved locally")
        }

    @Test
    fun `local navigation is dropped under external control`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val store = createStore {
                module(createNavigationModule {
                    rootGraph {
                        start(loginScreen)
                        screens(homeScreen, loginScreen)
                    }
                })
                coroutineContext(StandardTestDispatcher(testScheduler))
            }
            advanceUntilIdle()

            store.beginExternalControl()
            advanceUntilIdle()

            val outcome = store.selectLogic<NavigationLogic>().navigate { navigateTo("home") }
            advanceUntilIdle()

            assertEquals(NavigationOutcome.Dropped, outcome)
            assertEquals("login", store.selectState<NavigationState>().first().currentEntry.route)
        }

    @Test
    fun `guards are not evaluated under external control`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            var guardEvaluations = 0
            val store = createStore {
                module(createNavigationModule {
                    rootGraph {
                        start(loginScreen)
                        screens(loginScreen)
                        intercept(guard = {
                            guardEvaluations++
                            GuardResult.Reject
                        }) {
                            screens(homeScreen)
                        }
                    }
                })
                coroutineContext(StandardTestDispatcher(testScheduler))
            }
            advanceUntilIdle()

            store.beginExternalControl()
            advanceUntilIdle()

            store.selectLogic<NavigationLogic>().navigate { navigateTo("home") }
            advanceUntilIdle()

            assertEquals(0, guardEvaluations, "A follower must not run its own guards")
        }

    @Test
    fun `a store gated at construction never starts bootstrap`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            var selectorRuns = 0
            val store = createStore {
                module(PreGateModule)
                module(createNavigationModule {
                    loadingModal(overlay)
                    rootGraph {
                        start(route = {
                            selectorRuns++
                            homeScreen
                        })
                        screens(homeScreen, loginScreen)
                    }
                })
                coroutineContext(StandardTestDispatcher(testScheduler))
            }
            advanceUntilIdle()

            assertEquals(0, selectorRuns, "Entry selection must never begin on a follower")
            assertTrue(
                store.selectState<NavigationState>().first().isBootstrapping,
                "Loading overlay must stay up until the first projection arrives"
            )
        }

    @Test
    fun `leaving external control and resetting restores normal bootstrap`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            var selectorRuns = 0
            val store = createStore {
                module(PreGateModule)
                module(createNavigationModule {
                    loadingModal(overlay)
                    rootGraph {
                        start(route = {
                            selectorRuns++
                            homeScreen
                        })
                        screens(homeScreen, loginScreen)
                    }
                })
                coroutineContext(StandardTestDispatcher(testScheduler))
            }
            advanceUntilIdle()
            assertEquals(0, selectorRuns)

            store.endExternalControl()
            store.reset()
            advanceUntilIdle()

            assertEquals(1, selectorRuns, "A store handed back to local control must bootstrap normally")
            val state = store.selectState<NavigationState>().first()
            assertFalse(state.isBootstrapping)
            assertEquals("home", state.currentEntry.route)
        }
}
