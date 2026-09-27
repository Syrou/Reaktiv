package io.github.syrou.reaktiv.devtools.ui

import io.github.syrou.reaktiv.introspection.tooling.ToolingLogic
import io.github.syrou.reaktiv.core.util.selectLogic
import io.github.syrou.reaktiv.core.ExperimentalReaktivApi
import io.github.syrou.reaktiv.core.Store
import io.github.syrou.reaktiv.core.createStore
import io.github.syrou.reaktiv.core.util.selectState
import io.github.syrou.reaktiv.devtools.client.DevToolsConnection
import io.github.syrou.reaktiv.devtools.middleware.DevToolsConfig
import io.github.syrou.reaktiv.devtools.protocol.ClientRole
import io.github.syrou.reaktiv.devtools.server.DevToolsServer
import io.github.syrou.reaktiv.devtools.server.RunningDevToolsServer
import io.github.syrou.reaktiv.devtools.service.DevToolsService
import io.github.syrou.reaktiv.introspection.IntrospectionConfig
import io.github.syrou.reaktiv.introspection.PlatformContext
import io.github.syrou.reaktiv.introspection.tooling.ToolingState
import io.github.syrou.reaktiv.introspection.tooling.createToolingModule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@OptIn(ExperimentalReaktivApi::class)
class LateUiAttachTest {

    private lateinit var server: RunningDevToolsServer
    private var serverPort: Int = 0
    private val stores = mutableListOf<Store>()
    private val connections = mutableListOf<DevToolsConnection>()

    @BeforeTest
    fun startServer() = runBlocking {
        server = DevToolsServer.startEmbedded(port = 0)
        serverPort = server.port()
    }

    @AfterTest
    fun stopServer() {
        connections.forEach { runCatching { runBlocking { it.disconnect() } } }
        connections.clear()
        stores.forEach { runCatching { it.cleanup() } }
        stores.clear()
        server.stop()
    }

    private fun buildPublisher(clientId: String): Store = createStore {
        module(
            createToolingModule(
                IntrospectionConfig(
                    clientId = clientId,
                    clientName = clientId,
                    platform = "JVM",
                    installLogicTracing = false,
                    installStallWatchdog = false,
                    installCrashHandler = false
                ),
                PlatformContext()
            ) {
                install(
                    DevToolsService(
                        DevToolsConfig(
                            serverUrl = "ws://127.0.0.1:$serverPort/ws",
                            autoConnect = true,
                            autoReconnect = false,
                            defaultRole = ClientRole.PUBLISHER
                        )
                    )
                )
            }
        )
        coroutineContext(Dispatchers.Default)
    }.also(stores::add)

    private suspend fun openUi(): Store {
        val store = createStore {
            module(DevToolsUiModule)
            coroutineContext(Dispatchers.Default)
        }.also(stores::add)
        val connection = DevToolsConnection("ws://127.0.0.1:$serverPort/ws").also(connections::add)
        DevToolsUiModule.selectLogicTyped(store).setConnection(connection)
        connection.connect(DEVTOOLS_UI_CLIENT_ID, "DevTools UI", "JVM")
        return store
    }

    private suspend fun awaitPublishing(store: Store) {
        val reached = withTimeoutOrNull(20_000) {
            store.selectState<ToolingState>().first {
                it.services["devtools"]?.detail?.contains("publishing") == true
            }
        }
        assertNotNull(reached, "Timed out waiting for the publisher to publish")
    }

    private suspend fun awaitBaseline(ui: Store, publisherId: String): DevToolsUiState {
        val state = withTimeoutOrNull(20_000) {
            ui.selectState<DevToolsUiState>().first {
                it.selectedPublisher == publisherId && it.initialStateJson != "{}"
            }
        }
        return assertNotNull(state, "The UI never received a baseline from $publisherId")
    }

    private suspend fun assertObserving(publisherId: String) {
        val info = server.clientManager.getClient(DEVTOOLS_UI_CLIENT_ID)
        assertEquals(ClientRole.ORCHESTRATOR, info?.role)
        assertEquals(publisherId, info?.publisherClientId)
    }

    @Test
    fun `a ui opened after the publisher attaches to it and receives its baseline`() = runBlocking {
        val publisher = buildPublisher("running-publisher")
        awaitPublishing(publisher)

        val ui = openUi()

        awaitBaseline(ui, "running-publisher")
        assertObserving("running-publisher")
    }

    @Test
    fun `a ui opened after a marker was dropped still shows the marker`() = runBlocking<Unit> {
        val publisher = buildPublisher("marked-publisher")
        awaitPublishing(publisher)
        publisher.selectLogic<ToolingLogic>().addMarker("before the ui opened")

        val ui = openUi()

        val marked = withTimeoutOrNull(20_000) {
            ui.selectState<DevToolsUiState>().first { state -> state.markers.any { it.label == "before the ui opened" } }
        }
        assertNotNull(marked, "the marker in the device history never reached the UI")
    }

    @Test
    fun `a ui opened before the publisher attaches when the publisher arrives`() = runBlocking {
        val ui = openUi()
        val publisher = buildPublisher("arriving-publisher")
        awaitPublishing(publisher)

        awaitBaseline(ui, "arriving-publisher")
        assertObserving("arriving-publisher")
    }
}
