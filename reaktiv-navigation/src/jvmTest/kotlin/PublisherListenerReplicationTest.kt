import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import io.github.syrou.reaktiv.core.ExperimentalReaktivApi
import io.github.syrou.reaktiv.core.Middleware
import io.github.syrou.reaktiv.core.Module
import io.github.syrou.reaktiv.core.ModuleAction
import io.github.syrou.reaktiv.core.ModuleLogic
import io.github.syrou.reaktiv.core.ModuleState
import io.github.syrou.reaktiv.core.ModuleWithLogic
import io.github.syrou.reaktiv.core.Store
import io.github.syrou.reaktiv.core.StoreAccessor
import io.github.syrou.reaktiv.core.createStore
import io.github.syrou.reaktiv.core.util.ReaktivDebug
import io.github.syrou.reaktiv.core.util.reaktivJson
import io.github.syrou.reaktiv.core.util.selectLogic
import io.github.syrou.reaktiv.core.util.selectState
import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.createNavigationModule
import io.github.syrou.reaktiv.navigation.definition.LoadingModal
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.extension.navigation
import io.github.syrou.reaktiv.navigation.model.GuardResult
import io.github.syrou.reaktiv.navigation.param.Params
import io.github.syrou.reaktiv.navigation.transition.NavTransition
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.PolymorphicSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Serializable
data class ReplAuthState(val isAuthenticated: Boolean? = null) : ModuleState

sealed class ReplAuthAction : ModuleAction(ReplAuthModule::class) {
    data class SetAuthenticated(val value: Boolean) : ReplAuthAction()
}

class ReplAuthLogic(private val storeAccessor: StoreAccessor) : ModuleLogic() {
    suspend fun initializeSession() {
        storeAccessor.dispatch(ReplAuthAction.SetAuthenticated(true))
    }
}

object ReplAuthModule : ModuleWithLogic<ReplAuthState, ReplAuthAction, ReplAuthLogic> {
    override val initialState = ReplAuthState()
    override val reducer: (ReplAuthState, ReplAuthAction) -> ReplAuthState = { state, action ->
        when (action) {
            is ReplAuthAction.SetAuthenticated -> state.copy(isAuthenticated = action.value)
        }
    }
    override val createLogic: (StoreAccessor) -> ReplAuthLogic = { ReplAuthLogic(it) }
}

@Serializable
data class ReplGateState(val unused: Boolean = false) : ModuleState

sealed class ReplGateAction : ModuleAction(ReplGateModule::class) {
    data object Noop : ReplGateAction()
}

/**
 * Stands in for `ToolingLogic`, which gates the store from its own constructor when a client
 * is configured to start as a follower.
 */
@OptIn(ExperimentalReaktivApi::class)
class ReplGateLogic(storeAccessor: StoreAccessor) : ModuleLogic() {
    init {
        if (ReplGateModule.gateOnConstruction) {
            ReplGateModule.gateOnConstruction = false
            storeAccessor.asInternalOperations()?.markExternallyDriven()
        }
    }
}

object ReplGateModule : Module<ReplGateState, ReplGateAction> {
    var gateOnConstruction: Boolean = false

    override val initialState = ReplGateState()
    override val reducer: (ReplGateState, ReplGateAction) -> ReplGateState = { state, _ -> state }
    override val createLogic: (StoreAccessor) -> ModuleLogic = { ReplGateLogic(it) }
}

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalReaktivApi::class)
class PublisherListenerReplicationTest {

    private fun screen(route: String) = object : Screen {
        override val route = route
        override val enterTransition = NavTransition.None
        override val exitTransition = NavTransition.None

        @Composable
        override fun Content(params: Params) { Text(route) }
    }

    private fun loading(route: String) = object : LoadingModal {
        override val route = route
        override val enterTransition = NavTransition.None
        override val exitTransition = NavTransition.None

        @Composable
        override fun Content(params: Params) { Text(route) }
    }

    private val loginScreen = screen("login")
    private val newsScreen = screen("news")
    private val detailScreen = screen("detail")
    private val overlay = loading("loading")

    private class Counters {
        var selectorRuns = 0
        var guardRuns = 0
    }

    /**
     * Builds a store with the shape the report describes: an async start lambda that depends on
     * state only a dispatch can produce, and an intercept guard over a nested graph.
     */
    private fun buildStore(
        counters: Counters,
        dispatcher: CoroutineDispatcher,
        preGate: Boolean,
        captureStates: ((suspend () -> Map<String, ModuleState>) -> Unit)? = null
    ): Store {
        ReplGateModule.gateOnConstruction = preGate
        val capturing: Middleware = { action, getAllStates, _, updatedState ->
            captureStates?.invoke(getAllStates)
            updatedState(action)
        }
        return createStore {
            module(ReplGateModule)
            module(ReplAuthModule)
            module(createNavigationModule {
                loadingModal(overlay)
                rootGraph {
                    start(route = { store ->
                        counters.selectorRuns++
                        store.selectLogic<ReplAuthLogic>().initializeSession()
                        val authed = store.selectState<ReplAuthState>()
                            .mapNotNull { it.isAuthenticated }
                            .first()
                        if (authed) newsScreen else loginScreen
                    })
                    screens(loginScreen)

                    intercept(guard = { store ->
                        counters.guardRuns++
                        if (store.selectState<ReplAuthState>().first().isAuthenticated == true) {
                            GuardResult.Allow
                        } else {
                            GuardResult.RedirectTo("login")
                        }
                    }) {
                        graph("home") {
                            start(newsScreen)
                            screens(newsScreen, detailScreen)
                        }
                    }
                }
            })
            middlewares(capturing)
            coroutineContext(dispatcher)
        }
    }

    /**
     * Mirrors DevToolsService exactly: encode with the publisher's serializers, decode with the
     * follower's own, then project. A follower that does not register everything the publisher
     * sends fails here, which is the cross-build mismatch case.
     */
    private suspend fun project(publisherStates: Map<String, ModuleState>, publisher: Store, listener: Store) {
        val mapSerializer = MapSerializer(String.serializer(), PolymorphicSerializer(ModuleState::class))
        val stateJson = reaktivJson(publisher.serializersModule)
            .encodeToString(mapSerializer, publisherStates)
        val decoded: Map<String, ModuleState> = reaktivJson(listener.serializersModule)
            .decodeFromString(mapSerializer, stateJson)
        listener.applyExternalStates(decoded)
    }

    @BeforeTest
    fun setUp() {
        ReaktivDebug.enable()
        ReplGateModule.gateOnConstruction = false
    }

    @Test
    fun `a listener gated at startup lands on the publisher screen behind an intercept`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val publisherCounters = Counters()
            var publisherStates: (suspend () -> Map<String, ModuleState>)? = null

            val publisher = buildStore(publisherCounters, dispatcher, preGate = false) {
                if (publisherStates == null) publisherStates = it
            }
            advanceUntilIdle()

            publisher.navigation { navigateTo("home/news") }
            advanceUntilIdle()

            val publisherNav = publisher.selectState<NavigationState>().first()
            assertEquals("news", publisherNav.currentEntry.route)
            assertFalse(publisherNav.isBootstrapping)
            assertTrue(publisherCounters.guardRuns > 0, "Publisher must have evaluated its own guard")

            val listenerCounters = Counters()
            val listener = buildStore(listenerCounters, dispatcher, preGate = true)
            advanceUntilIdle()

            assertTrue(listener.isExternallyDriven, "Listener must be gated at construction")
            assertEquals(0, listenerCounters.selectorRuns, "Listener must not run the start lambda")
            assertEquals(0, listenerCounters.guardRuns, "Listener must not run the intercept guard")

            project(publisherStates!!.invoke(), publisher, listener)
            advanceUntilIdle()

            val listenerNav = listener.selectState<NavigationState>().first()
            assertEquals(publisherNav.currentEntry.route, listenerNav.currentEntry.route)
            assertEquals(
                publisherNav.currentEntry.navigatable.route,
                listenerNav.currentEntry.navigatable.route
            )
            assertEquals(publisherNav.backStack.size, listenerNav.backStack.size)
            assertFalse(listenerNav.isBootstrapping, "Projection must clear the loading placeholder")
            assertFalse(listenerNav.isEvaluatingNavigation)
            assertEquals(0, listenerCounters.selectorRuns)
            assertEquals(0, listenerCounters.guardRuns)

            publisher.cleanup()
            listener.cleanup()
        }

    @Test
    fun `a listener that already booted converges on the publisher screen`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val publisherCounters = Counters()
            var publisherStates: (suspend () -> Map<String, ModuleState>)? = null

            val publisher = buildStore(publisherCounters, dispatcher, preGate = false) {
                if (publisherStates == null) publisherStates = it
            }
            advanceUntilIdle()
            publisher.navigation { navigateTo("home/detail") }
            advanceUntilIdle()

            val listenerCounters = Counters()
            val listener = buildStore(listenerCounters, dispatcher, preGate = false)
            advanceUntilIdle()

            assertEquals(1, listenerCounters.selectorRuns, "Listener booted locally before following")

            listener.beginExternalControl()
            advanceUntilIdle()

            project(publisherStates!!.invoke(), publisher, listener)
            advanceUntilIdle()

            val publisherNav = publisher.selectState<NavigationState>().first()
            val listenerNav = listener.selectState<NavigationState>().first()
            assertEquals("detail", publisherNav.currentEntry.route)
            assertEquals(publisherNav.currentEntry.route, listenerNav.currentEntry.route)
            assertFalse(listenerNav.isBootstrapping)

            publisher.cleanup()
            listener.cleanup()
        }

    @Test
    fun `entering external control mid start-lambda does not deadlock the listener`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val counters = Counters()

            val listener = createStore {
                module(ReplAuthModule)
                module(createNavigationModule {
                    loadingModal(overlay)
                    rootGraph {
                        start(route = { store ->
                            counters.selectorRuns++
                            store.selectState<ReplAuthState>()
                                .mapNotNull { it.isAuthenticated }
                                .first()
                            newsScreen
                        })
                        screens(loginScreen, newsScreen)
                    }
                })
                coroutineContext(dispatcher)
            }
            advanceUntilIdle()

            assertEquals(1, counters.selectorRuns, "Start lambda began before the role arrived")
            assertTrue(
                listener.selectState<NavigationState>().first().isBootstrapping,
                "Start lambda is parked on state a dispatch would have produced"
            )

            listener.beginExternalControl()
            advanceUntilIdle()

            val nav = listener.selectState<NavigationState>().first()
            assertFalse(nav.isBootstrapping, "Entering external control must release the parked bootstrap")
            assertFalse(nav.isEvaluatingNavigation)

            listener.cleanup()
        }
}
