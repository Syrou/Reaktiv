package io.github.syrou.reaktiv.devtools

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import io.github.syrou.reaktiv.core.ExperimentalReaktivApi
import io.github.syrou.reaktiv.core.ModuleAction
import io.github.syrou.reaktiv.core.ModuleLogic
import io.github.syrou.reaktiv.core.ModuleState
import io.github.syrou.reaktiv.core.ModuleWithLogic
import io.github.syrou.reaktiv.core.Store
import io.github.syrou.reaktiv.core.StoreAccessor
import io.github.syrou.reaktiv.core.createStore
import io.github.syrou.reaktiv.core.util.selectLogic
import io.github.syrou.reaktiv.core.util.selectState
import io.github.syrou.reaktiv.devtools.client.DevToolsConnection
import io.github.syrou.reaktiv.devtools.middleware.DevToolsConfig
import io.github.syrou.reaktiv.devtools.protocol.ClientRole
import io.github.syrou.reaktiv.devtools.protocol.DevToolsMessage
import io.github.syrou.reaktiv.devtools.server.DevToolsServer
import io.github.syrou.reaktiv.devtools.server.RunningDevToolsServer
import io.github.syrou.reaktiv.devtools.service.DevToolsService
import io.github.syrou.reaktiv.introspection.IntrospectionConfig
import io.github.syrou.reaktiv.introspection.capture.SessionHistory
import io.github.syrou.reaktiv.introspection.PlatformContext
import io.github.syrou.reaktiv.introspection.tooling.ToolingState
import io.github.syrou.reaktiv.introspection.tooling.createToolingModule
import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.createNavigationModule
import io.github.syrou.reaktiv.navigation.definition.LoadingModal
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.extension.navigation
import io.github.syrou.reaktiv.navigation.model.GuardResult
import io.github.syrou.reaktiv.navigation.param.Params
import io.github.syrou.reaktiv.navigation.transition.NavTransition
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@Serializable
data class E2EAuthState(
    val isAuthenticated: Boolean? = null,
    val marker: String? = null
) : ModuleState

sealed class E2EAuthAction : ModuleAction(E2EAuthModule::class) {
    data class SetAuthenticated(val value: Boolean) : E2EAuthAction()
    data class SetMarker(val value: String) : E2EAuthAction()
}

class E2EAuthLogic(private val storeAccessor: StoreAccessor) : ModuleLogic() {
    suspend fun initializeSession() {
        storeAccessor.dispatch(E2EAuthAction.SetAuthenticated(true))
    }
}

object E2EAuthModule : ModuleWithLogic<E2EAuthState, E2EAuthAction, E2EAuthLogic> {
    override val initialState = E2EAuthState()
    override val reducer: (E2EAuthState, E2EAuthAction) -> E2EAuthState = { state, action ->
        when (action) {
            is E2EAuthAction.SetAuthenticated -> state.copy(isAuthenticated = action.value)
            is E2EAuthAction.SetMarker -> state.copy(marker = action.value)
        }
    }
    override val createLogic: (StoreAccessor) -> E2EAuthLogic = { E2EAuthLogic(it) }
}

/**
 * Drives a real [DevToolsServer] over real websockets with two real stores, so the transport,
 * the role handshake, external control and state projection are all exercised together.
 *
 * The in-process tests in reaktiv-navigation cover the same replication semantics without the
 * wire. This one exists because the wire had no coverage at all.
 */
@OptIn(ExperimentalReaktivApi::class)
class EndToEndReplicationTest {

    private lateinit var server: RunningDevToolsServer
    private val stores = mutableListOf<Store>()

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

    class Counters {
        @Volatile var selectorRuns = 0
        @Volatile var guardRuns = 0
    }

    private companion object {
        const val PUBLISHER_MARKER = "only-the-publisher-produces-this"
    }

    private fun buildClient(
        clientId: String,
        role: ClientRole,
        counters: Counters
    ): Store {
        val devToolsConfig = DevToolsConfig(
            serverUrl = "ws://127.0.0.1:${server.port}/ws",
            autoConnect = true,
            autoReconnect = false,
            defaultRole = role
        )
        val introspectionConfig = IntrospectionConfig(
            clientId = clientId,
            clientName = clientId,
            platform = "JVM",
            installLogicTracing = false,
            installStallWatchdog = false,
            installCrashHandler = false
        )
        return createStore {
            module(
                createToolingModule(introspectionConfig, PlatformContext()) {
                    install(DevToolsService(devToolsConfig))
                }
            )
            module(E2EAuthModule)
            module(createNavigationModule {
                loadingModal(overlay)
                rootGraph {
                    start(route = { store ->
                        counters.selectorRuns++
                        store.selectLogic<E2EAuthLogic>().initializeSession()
                        val authed = store.selectState<E2EAuthState>()
                            .mapNotNull { it.isAuthenticated }
                            .first()
                        if (authed) newsScreen else loginScreen
                    })
                    screens(loginScreen)

                    intercept(guard = { store ->
                        counters.guardRuns++
                        if (store.selectState<E2EAuthState>().first().isAuthenticated == true) {
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
            coroutineContext(Dispatchers.Default)
        }.also { stores.add(it) }
    }

    /**
     * Suspends on the state flow until it satisfies [predicate], with a timeout only as a
     * failure report.
     *
     * Nothing here waits out a duration and re-checks. Polling would both make the test a
     * timing bet and actively perturb what it measures, since each `selectState` takes the
     * store's state mutex, the same one a projection needs in order to land.
     */
    private suspend fun <T> awaitState(
        flow: StateFlow<T>,
        description: String,
        timeoutMs: Long = 15_000,
        diagnostics: (suspend () -> String)? = null,
        predicate: (T) -> Boolean
    ): T {
        val result = withTimeoutOrNull(timeoutMs) { flow.first { predicate(it) } }
        assertNotNull(
            result,
            "Timed out waiting for: $description${diagnostics?.let { "\n" + it() } ?: ""}"
        )
        return result
    }

    private suspend fun describe(label: String, store: Store): String {
        val nav = store.selectState<NavigationState>().first()
        val tooling = store.selectState<ToolingState>().first()
        return "$label: route=${nav.currentEntry.route} " +
            "bootstrapping=${nav.isBootstrapping} evaluating=${nav.isEvaluatingNavigation} " +
            "externallyDriven=${store.isExternallyDriven} " +
            "backStack=${nav.backStack.map { it.route }} " +
            "tooling=${tooling.services}"
    }

    @BeforeTest
    fun startServer() {
        DevToolsServer.resetState()
        server = DevToolsServer.startEmbedded(port = 0)
    }

    @AfterTest
    fun stopServer() {
        stores.forEach { runCatching { it.cleanup() } }
        stores.clear()
        server.stop()
    }

    private suspend fun startPublisher(clientId: String, counters: Counters): Store {
        val publisher = buildClient(clientId, ClientRole.PUBLISHER, counters)
        awaitState(
            publisher.selectState<ToolingState>(),
            description = "publisher to report publishing"
        ) { it.services["devtools"]?.detail?.contains("publishing") == true }

        publisher.navigation { navigateTo("home/detail") }
        awaitState(
            publisher.selectState<NavigationState>(),
            description = "publisher to reach home/detail"
        ) { it.currentEntry.route == "detail" && !it.isBootstrapping }

        publisher.dispatch(E2EAuthAction.SetMarker(PUBLISHER_MARKER))
        awaitState(
            publisher.selectState<E2EAuthState>(),
            description = "publisher marker to settle"
        ) { it.marker == PUBLISHER_MARKER }

        assertTrue(counters.selectorRuns > 0, "Publisher must run its own start lambda")
        assertTrue(counters.guardRuns > 0, "Publisher must evaluate its own intercept guard")
        return publisher
    }

    /**
     * Asserts the listener arrived where it is purely by projection.
     *
     * `detail` is unreachable from the listener's own start lambda, which resolves to `news`,
     * and sits behind an intercept the listener must never evaluate. The marker is a value only
     * the publisher ever produces, so finding it proves the state came across the wire rather
     * than being reconstructed locally.
     */
    private suspend fun assertReplicatedNotLocallyDerived(
        publisher: Store,
        listener: Store,
        listenerCounters: Counters
    ) {
        assertEquals(0, listenerCounters.selectorRuns, "Listener must never run the start lambda")
        assertEquals(0, listenerCounters.guardRuns, "Listener must never run the intercept guard")
        assertTrue(listener.isExternallyDriven, "Listener must be under external control")

        val listenerAuth = listener.selectState<E2EAuthState>().first()
        assertEquals(PUBLISHER_MARKER, listenerAuth.marker, "Listener must hold the publisher's state")
        assertEquals(true, listenerAuth.isAuthenticated)

        val publisherNav = publisher.selectState<NavigationState>().first()
        val listenerNav = listener.selectState<NavigationState>().first()
        assertEquals(publisherNav.currentEntry.route, listenerNav.currentEntry.route)
        assertEquals(
            publisherNav.backStack.map { it.route },
            listenerNav.backStack.map { it.route }
        )
        assertFalse(listenerNav.isBootstrapping, "Projection must clear the loading placeholder")
        assertFalse(listenerNav.isEvaluatingNavigation)
    }

    @Test
    fun `a listener started after the publisher lands on the publisher screen behind an intercept`() =
        runBlocking {
            val publisher = startPublisher("e2e-publisher", Counters())

            val listenerCounters = Counters()
            val listener = buildClient("e2e-listener", ClientRole.LISTENER, listenerCounters)

            awaitState(
                listener.selectState<NavigationState>(),
                description = "listener to replicate the publisher screen",
                diagnostics = {
                    describe("listener", listener) + "\n" + describe("publisher", publisher)
                }
            ) { it.currentEntry.route == "detail" }

            assertReplicatedNotLocallyDerived(publisher, listener, listenerCounters)
        }

    @Test
    fun `a listener follows subsequent publisher navigation`() = runBlocking {
        val publisher = startPublisher("e2e-publisher-2", Counters())

        val listenerCounters = Counters()
        val listener = buildClient("e2e-listener-2", ClientRole.LISTENER, listenerCounters)
        val listenerNav = listener.selectState<NavigationState>()
        awaitState(
            listenerNav,
            description = "listener initial replication",
            diagnostics = { describe("listener", listener) + "\n" + describe("publisher", publisher) }
        ) { it.currentEntry.route == "detail" }
        assertReplicatedNotLocallyDerived(publisher, listener, listenerCounters)

        publisher.navigation { navigateBack() }
        awaitState(
            publisher.selectState<NavigationState>(),
            description = "publisher to go back to home/news"
        ) { it.currentEntry.route == "news" }

        awaitState(
            listenerNav,
            description = "listener to follow the publisher back",
            diagnostics = { describe("listener", listener) + "\n" + describe("publisher", publisher) }
        ) { it.currentEntry.route == "news" }
        assertEquals(0, listenerCounters.selectorRuns, "Listener must never run the start lambda")
        assertEquals(0, listenerCounters.guardRuns, "Listener must never run the intercept guard")
    }

    /**
     * An orchestrator attaching after the publisher must receive a full state baseline.
     *
     * Without it the wasm UI reconstructs from `{}` and can only ever show the modules that
     * happen to appear in later deltas, never the whole application state.
     */
    @Test
    fun `an orchestrator attaching later receives a full state baseline`() = runBlocking {
        val publisher = startPublisher("e2e-publisher-3", Counters())

        val received = CompletableDeferred<SessionHistory>()
        val ui = DevToolsConnection("ws://127.0.0.1:${server.port}/ws")
        try {
            ui.connect("devtools-ui", "devtools-ui", "wasm")
            ui.observeMessages { message ->
                when (message) {
                    is DevToolsMessage.SessionHistorySync -> received.complete(message.history)
                    is DevToolsMessage.SessionHistoryChunk ->
                        if (message.chunkIndex == 0) received.complete(message.history)
                    else -> {}
                }
            }
            ui.send(
                DevToolsMessage.RoleAssignment(
                    targetClientId = "devtools-ui",
                    role = ClientRole.ORCHESTRATOR,
                    publisherClientId = "e2e-publisher-3"
                )
            )

            val history = withTimeoutOrNull(15_000) { received.await() }
            assertNotNull(history, "Orchestrator must be sent a session history baseline on attach")

            assertTrue(
                history.initialStateJson != "{}" && history.initialStateJson.isNotBlank(),
                "Baseline must be a real state tree, was ${history.initialStateJson}"
            )
            val baseline = Json.parseToJsonElement(history.initialStateJson).jsonObject
            val publisherModules = publisher.selectState<NavigationState>().value.let {
                setOf(
                    NavigationState::class.qualifiedName,
                    E2EAuthState::class.qualifiedName,
                    ToolingState::class.qualifiedName
                )
            }
            publisherModules.forEach { module ->
                assertTrue(
                    baseline.containsKey(module),
                    "Baseline must contain $module, had ${baseline.keys}"
                )
            }
        } finally {
            ui.disconnect()
        }
    }

    @Test
    fun `a listener with no publisher recovers to local control and boots normally`() = runBlocking {
        val counters = Counters()
        val listener = buildClient("e2e-lonely-listener", ClientRole.LISTENER, counters)
        listener.initialized.first { it }

        assertTrue(listener.isExternallyDriven, "Listener must be gated at construction")

        val recovered = awaitState(
            listener.selectState<NavigationState>(),
            timeoutMs = 8_000,
            description = "listener to recover and bootstrap locally without waiting out the backstop",
            diagnostics = { describe("listener", listener) }
        ) { !it.isBootstrapping && it.currentEntry.route == "news" }

        assertFalse(listener.isExternallyDriven, "Store must be handed back to local control")
        assertEquals("news", recovered.currentEntry.route)
        assertTrue(counters.selectorRuns > 0, "Recovered client must run its own start lambda")
    }
}
