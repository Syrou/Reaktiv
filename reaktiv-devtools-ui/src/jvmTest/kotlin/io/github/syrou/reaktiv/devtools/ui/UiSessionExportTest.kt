package io.github.syrou.reaktiv.devtools.ui

import io.github.syrou.reaktiv.core.ExperimentalReaktivApi
import io.github.syrou.reaktiv.core.Store
import io.github.syrou.reaktiv.core.createStore
import io.github.syrou.reaktiv.core.util.ReaktivDebug
import io.github.syrou.reaktiv.core.util.reaktivJson
import io.github.syrou.reaktiv.core.util.selectState
import io.github.syrou.reaktiv.devtools.client.DevToolsConnection
import io.github.syrou.reaktiv.devtools.middleware.DevToolsConfig
import io.github.syrou.reaktiv.devtools.protocol.ClientRole
import io.github.syrou.reaktiv.devtools.server.DevToolsServer
import io.github.syrou.reaktiv.devtools.server.RunningDevToolsServer
import io.github.syrou.reaktiv.devtools.service.DevToolsService
import io.github.syrou.reaktiv.introspection.IntrospectionConfig
import io.github.syrou.reaktiv.introspection.PlatformContext
import io.github.syrou.reaktiv.introspection.protocol.CapturedLog
import io.github.syrou.reaktiv.introspection.protocol.ExportedClientInfo
import io.github.syrou.reaktiv.introspection.protocol.SessionData
import io.github.syrou.reaktiv.introspection.protocol.SessionExport
import io.github.syrou.reaktiv.introspection.protocol.SessionExportFormat
import io.github.syrou.reaktiv.introspection.protocol.SessionMarker
import io.github.syrou.reaktiv.introspection.tooling.ToolingState
import io.github.syrou.reaktiv.introspection.tooling.createToolingModule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalReaktivApi::class)
class UiSessionExportTest {

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

    private fun buildPublisher(clientId: String, allowRemoteRequests: Boolean = true): Store = createStore {
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
                            defaultRole = ClientRole.PUBLISHER,
                            allowRemoteRequests = allowRemoteRequests
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

    private suspend fun awaitSelected(ui: Store, publisherId: String) {
        val reached = withTimeoutOrNull(20_000) {
            ui.selectState<DevToolsUiState>().first { it.selectedPublisher == publisherId }
        }
        assertNotNull(reached, "The UI never selected $publisherId")
    }

    @Test
    fun `an export from the ui is the device's own export with its logs`() = runBlocking {
        val device = buildPublisher("export-device")
        awaitPublishing(device)
        ReaktivDebug.log("INFO", "Checkout", "a line the device logged")
        val ui = openUi()
        awaitSelected(ui, "export-device")

        val exported = json.decodeFromString<SessionExport>(DevToolsUiModule.selectLogicTyped(ui).exportSession())

        assertEquals("export-device", exported.clientInfo.clientId)
        assertTrue(
            exported.session.logs.any { it.message == "a line the device logged" },
            "the export lost the device logs: ${exported.session.logs}"
        )
    }

    @Test
    fun `a device that refuses remote requests is exported from what the ui observed`() = runBlocking {
        val device = buildPublisher("closed-device", allowRemoteRequests = false)
        awaitPublishing(device)
        val ui = openUi()
        awaitSelected(ui, "closed-device")
        ReaktivDebug.log("INFO", "Checkout", "observed by the ui")
        val observed = withTimeoutOrNull(20_000) {
            ui.selectState<DevToolsUiState>().first { state -> state.deviceLogs.any { it.message == "observed by the ui" } }
        }
        assertNotNull(observed, "the UI never received the log line")

        val exported = json.decodeFromString<SessionExport>(DevToolsUiModule.selectLogicTyped(ui).exportSession())

        assertEquals("closed-device", exported.clientInfo.clientId)
        assertTrue(exported.session.logs.any { it.message == "observed by the ui" })
    }

    @Test
    fun `an imported session exports as imported with the markers added since`() = runBlocking {
        val ui = openUi()
        val recorded = SessionExport(
            version = SessionExportFormat.VERSION,
            sessionId = "recorded-1",
            exportedAt = 1L,
            clientInfo = ExportedClientInfo(clientId = "phone", clientName = "Phone", platform = "Android"),
            session = SessionData(
                startTime = 0L,
                endTime = 1L,
                initialStateJson = "{}",
                actions = emptyList(),
                logicStartedEvents = emptyList(),
                logicCompletedEvents = emptyList(),
                logicFailedEvents = emptyList(),
                markers = listOf(SessionMarker(id = "m1", label = "device marker", timestampMs = 5L, source = "device")),
                logs = listOf(CapturedLog("INFO", "Checkout", "recorded line", 1L))
            )
        )
        val logic = DevToolsUiModule.selectLogicTyped(ui)
        logic.importGhostSession(json.encodeToString(recorded))
        awaitSelected(ui, "ghost-recorded-1")
        ui.dispatch(
            DevToolsUiAction.AddMarker(SessionMarker(id = "m2", label = "analyst note", timestampMs = 9L, source = "analyst"))
        )
        val marked = withTimeoutOrNull(20_000) {
            ui.selectState<DevToolsUiState>().first { state -> state.markers.any { it.id == "m2" } }
        }
        assertNotNull(marked, "the analyst marker never reached state")

        val exported = json.decodeFromString<SessionExport>(logic.exportSession())

        assertEquals("phone", exported.clientInfo.clientId)
        assertEquals(listOf("recorded line"), exported.session.logs.map { it.message })
        assertEquals(setOf("m1", "m2"), exported.session.markers.map { it.id }.toSet())
    }
}
