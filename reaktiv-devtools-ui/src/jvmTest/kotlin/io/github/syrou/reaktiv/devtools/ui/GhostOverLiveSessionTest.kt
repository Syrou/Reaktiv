package io.github.syrou.reaktiv.devtools.ui

import io.github.syrou.reaktiv.core.ExperimentalReaktivApi
import io.github.syrou.reaktiv.core.Store
import io.github.syrou.reaktiv.core.createStore
import io.github.syrou.reaktiv.core.util.reaktivJson
import io.github.syrou.reaktiv.core.util.selectState
import io.github.syrou.reaktiv.devtools.E2EAuthAction
import io.github.syrou.reaktiv.devtools.E2EAuthModule
import io.github.syrou.reaktiv.devtools.client.DevToolsConnection
import io.github.syrou.reaktiv.devtools.middleware.DevToolsConfig
import io.github.syrou.reaktiv.devtools.protocol.ClientRole
import io.github.syrou.reaktiv.devtools.server.DevToolsServer
import io.github.syrou.reaktiv.devtools.server.RunningDevToolsServer
import io.github.syrou.reaktiv.devtools.service.DevToolsService
import io.github.syrou.reaktiv.introspection.IntrospectionConfig
import io.github.syrou.reaktiv.introspection.PlatformContext
import io.github.syrou.reaktiv.introspection.protocol.CapturedAction
import io.github.syrou.reaktiv.introspection.protocol.ExportedClientInfo
import io.github.syrou.reaktiv.introspection.protocol.SessionData
import io.github.syrou.reaktiv.introspection.protocol.SessionExport
import io.github.syrou.reaktiv.introspection.tooling.ToolingState
import io.github.syrou.reaktiv.introspection.tooling.createToolingModule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@OptIn(ExperimentalReaktivApi::class)
class GhostOverLiveSessionTest {

    private lateinit var server: RunningDevToolsServer
    private var serverPort: Int = 0
    private val stores = mutableListOf<Store>()
    private val connections = mutableListOf<DevToolsConnection>()
    private val json = reaktivJson()

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
        module(E2EAuthModule)
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

    private suspend fun Store.awaitUi(what: String, predicate: (DevToolsUiState) -> Boolean): DevToolsUiState {
        val reached = withTimeoutOrNull(20_000) { selectState<DevToolsUiState>().first(predicate) }
        return assertNotNull(reached, "the ui never reached: $what")
    }

    private fun recordedSession(sessionId: String = "recorded"): String = json.encodeToString(
        SessionExport(
            sessionId = sessionId,
            exportedAt = 2L,
            clientInfo = ExportedClientInfo(clientId = "old-device", clientName = "Old device", platform = "JVM"),
            session = SessionData(
                startTime = 0L,
                endTime = 1L,
                initialStateJson = """{"GhostOnly":{"x":1}}""",
                actions = listOf(
                    CapturedAction(
                        clientId = "old-device",
                        timestamp = 1L,
                        actionType = "GhostStep",
                        actionData = "{}",
                        stateDeltaJson = """{"x":2}""",
                        moduleName = "GhostOnly"
                    )
                ),
                logicStartedEvents = emptyList(),
                logicCompletedEvents = emptyList(),
                logicFailedEvents = emptyList()
            )
        )
    )

    @Test
    fun `an imported session stays in view on its own while a live device publishes`() = runBlocking {
        val publisher = buildPublisher("live")
        awaitPublishing(publisher)
        publisher.dispatchAndAwait(E2EAuthAction.SetMarker("live-step"))
        val ui = openUi()
        ui.awaitUi("the live history") { state ->
            state.selectedPublisher == "live" && state.actionStateHistory.any { it.actionType.contains("SetMarker") }
        }

        DevToolsUiModule.selectLogicTyped(ui).importGhostSession(recordedSession())
        ui.awaitUi("the ghost in the client list") { state ->
            state.connectedClients.any { it.clientId == "ghost-recorded" }
        }
        delay(500)

        val viewing = ui.selectState<DevToolsUiState>().value
        assertEquals("ghost-recorded", viewing.selectedPublisher)
        assertEquals(listOf("GhostStep"), viewing.actionStateHistory.map { it.actionType })
    }

    @Test
    fun `removing an imported session returns the view to the live device`() = runBlocking {
        val publisher = buildPublisher("live")
        awaitPublishing(publisher)
        publisher.dispatchAndAwait(E2EAuthAction.SetMarker("live-step"))
        val ui = openUi()
        ui.awaitUi("the live history") { it.selectedPublisher == "live" && it.initialStateJson != "{}" }
        val logic = DevToolsUiModule.selectLogicTyped(ui)
        logic.importGhostSession(recordedSession())
        ui.awaitUi("the ghost in view") { it.selectedPublisher == "ghost-recorded" }

        logic.removeGhostDevice("ghost-recorded")

        val back = ui.awaitUi("the live device back in view") { state ->
            state.selectedPublisher == "live" &&
                !state.initialStateJson.contains("GhostOnly") &&
                state.actionStateHistory.none { it.actionType == "GhostStep" }
        }
        assertNull(back.activeGhostId)
        assertFalse(back.timeTravelEnabled)
        val info = server.clientManager.getClient(DEVTOOLS_UI_CLIENT_ID)
        assertEquals("live", info?.publisherClientId)
    }

    @Test
    fun `the latest imported session stays in view when the client list changes`() = runBlocking {
        val ui = openUi()
        ui.awaitUi("a connection") { state -> state.connectedClients.any { it.clientId == DEVTOOLS_UI_CLIENT_ID } }
        val logic = DevToolsUiModule.selectLogicTyped(ui)
        logic.importGhostSession(recordedSession("first"))
        ui.awaitUi("the first ghost listed") { state -> state.connectedClients.any { it.clientId == "ghost-first" } }
        logic.importGhostSession(recordedSession("second"))
        ui.awaitUi("the second ghost listed") { state -> state.connectedClients.any { it.clientId == "ghost-second" } }

        val bystander = DevToolsConnection("ws://127.0.0.1:$serverPort/ws").also(connections::add)
        bystander.connect("bystander", "Bystander", "JVM")
        ui.awaitUi("the bystander listed") { state -> state.connectedClients.any { it.clientId == "bystander" } }
        delay(500)

        assertEquals("ghost-second", ui.selectState<DevToolsUiState>().value.selectedPublisher)
    }
}
