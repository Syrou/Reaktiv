package io.github.syrou.reaktiv.devtools.ui

import io.github.syrou.reaktiv.core.ExperimentalReaktivApi
import io.github.syrou.reaktiv.core.Store
import io.github.syrou.reaktiv.core.createStore
import io.github.syrou.reaktiv.core.util.reaktivJson
import io.github.syrou.reaktiv.core.util.selectState
import io.github.syrou.reaktiv.devtools.client.DevToolsConnection
import io.github.syrou.reaktiv.devtools.protocol.ClientRole
import io.github.syrou.reaktiv.devtools.protocol.DevToolsMessage
import io.github.syrou.reaktiv.devtools.server.DevToolsServer
import io.github.syrou.reaktiv.devtools.server.RunningDevToolsServer
import io.github.syrou.reaktiv.introspection.network.NetworkBodyPart
import io.github.syrou.reaktiv.introspection.network.NetworkRequestCapture
import io.github.syrou.reaktiv.introspection.protocol.CapturedAction
import io.github.syrou.reaktiv.introspection.protocol.DeltaKind
import io.github.syrou.reaktiv.introspection.protocol.ExportedClientInfo
import io.github.syrou.reaktiv.introspection.protocol.SessionData
import io.github.syrou.reaktiv.introspection.protocol.SessionExport
import io.github.syrou.reaktiv.introspection.protocol.SessionExportFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalReaktivApi::class)
class UiSideEffectsTest {

    private val stores = mutableListOf<Store>()
    private val connections = mutableListOf<DevToolsConnection>()
    private var server: RunningDevToolsServer? = null

    @AfterTest
    fun tearDown() {
        connections.forEach { runCatching { runBlocking { it.disconnect() } } }
        connections.clear()
        stores.forEach { runCatching { it.cleanup() } }
        stores.clear()
        server?.stop()
    }

    private fun uiStore(): Store = createStore {
        module(DevToolsUiModule)
        coroutineContext(Dispatchers.Default)
    }.also(stores::add)

    private suspend fun Store.awaitUi(what: String, predicate: (DevToolsUiState) -> Boolean): DevToolsUiState {
        val reached = withTimeoutOrNull(10_000) { selectState<DevToolsUiState>().first(predicate) }
        return assertNotNull(reached, "the ui never reached: $what")
    }

    private fun counter(count: Int) = CapturedAction(
        clientId = "phone",
        timestamp = count.toLong(),
        actionType = "Increment",
        actionData = "{}",
        stateDeltaJson = "{\"count\":$count}",
        moduleName = "Counter",
        deltaKind = DeltaKind.FULL
    )

    @Test
    fun `autoplay steps to the last action and stops without a composable driving it`() = runBlocking<Unit> {
        val store = uiStore()
        store.dispatchAndAwait(DevToolsUiAction.BulkAddActionStateEvents(listOf(counter(1), counter(2), counter(3))))
        store.dispatchAndAwait(DevToolsUiAction.SelectAction(0))
        store.dispatchAndAwait(DevToolsUiAction.SetPlaybackSpeed(100f))

        store.dispatch(DevToolsUiAction.SetAutoPlaying(true))

        store.awaitUi("autoplay to reach the last action") { !it.autoPlaying && it.selectedActionIndex == 2 }
    }

    @Test
    fun `selecting a truncated request starts loading its body`() = runBlocking<Unit> {
        val store = uiStore()
        store.dispatchAndAwait(DevToolsUiAction.SelectPublisher("ghost-1"))
        store.dispatchAndAwait(DevToolsUiAction.EnableTimeTravelWithGhost("ghost-1"))
        val exchange = NetworkRequestCapture(
            id = "req-1",
            startedAtMs = 0L,
            durationMs = 5L,
            method = "GET",
            url = "https://example.com/big",
            responseBody = "{\"partial\":",
            responseBodyTruncated = true
        )
        store.dispatchAndAwait(DevToolsUiAction.AppendNetworkEvents(listOf(NetworkEventRow("ghost-1", exchange))))

        store.dispatch(DevToolsUiAction.SelectNetworkRequest("req-1"))

        store.awaitUi("a body load for the selected request") {
            networkBodyKey("req-1", NetworkBodyPart.RESPONSE) in it.networkBodies
        }
    }

    @Test
    fun `a time travel seek sends the state at that action to followers`() = runBlocking<Unit> {
        val running = DevToolsServer.startEmbedded(port = 0).also { server = it }
        val url = "ws://127.0.0.1:${running.port()}/ws"
        val ui = uiStore()
        val logic = DevToolsUiModule.selectLogicTyped(ui)
        logic.connect(url)
        ui.awaitUi("the ui to connect") { it.connectionState.name == "CONNECTED" }

        val recording = SessionExport(
            version = SessionExportFormat.VERSION,
            sessionId = "travel",
            exportedAt = 1L,
            clientInfo = ExportedClientInfo(clientId = "phone", clientName = "Phone", platform = "Android"),
            session = SessionData(
                startTime = 0L,
                endTime = 3L,
                initialStateJson = "{\"Counter\":{\"count\":0}}",
                actions = listOf(counter(1), counter(2), counter(3)),
                logicStartedEvents = emptyList(),
                logicCompletedEvents = emptyList(),
                logicFailedEvents = emptyList()
            )
        )
        logic.importGhostSession(reaktivJson().encodeToString(recording))
        val ghostId = "ghost-travel"
        val linked = withTimeoutOrNull(10_000) {
            while (running.clientManager.getClient(DEVTOOLS_UI_CLIENT_ID)?.publisherClientId != ghostId) delay(20)
            true
        }
        assertNotNull(linked, "the ui never orchestrated the imported session")

        val follower = DevToolsConnection(url).also(connections::add)
        val inbox = Channel<DevToolsMessage>(Channel.UNLIMITED)
        follower.observeMessages { inbox.send(it) }
        follower.connect("follower", "follower", "JVM")
        follower.send(DevToolsMessage.RoleAssignment(targetClientId = "follower", role = ClientRole.LISTENER))
        val following = withTimeoutOrNull(10_000) {
            while (running.clientManager.getClient("follower")?.publisherClientId != ghostId) delay(20)
            true
        }
        assertNotNull(following, "the follower never followed the imported session")

        ui.dispatch(DevToolsUiAction.SetTimeTravelPosition(0))

        val sync = withTimeoutOrNull(10_000) {
            var hit: DevToolsMessage.StateSync? = null
            while (hit == null) hit = (inbox.receive() as? DevToolsMessage.StateSync)?.takeIf { it.timestamp == 1L }
            hit
        }
        val received = assertNotNull(sync, "the follower never received the state at the first action")
        assertEquals(ghostId, received.fromClientId)
        assertTrue(received.stateJson.contains("\"count\":1"), received.stateJson)
    }
}
