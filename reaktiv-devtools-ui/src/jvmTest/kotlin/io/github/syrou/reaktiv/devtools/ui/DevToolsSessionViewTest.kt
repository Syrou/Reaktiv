package io.github.syrou.reaktiv.devtools.ui

import io.github.syrou.reaktiv.core.ExperimentalReaktivApi
import io.github.syrou.reaktiv.core.Store
import io.github.syrou.reaktiv.core.createStore
import io.github.syrou.reaktiv.core.util.selectState
import io.github.syrou.reaktiv.devtools.client.DevToolsConnection
import io.github.syrou.reaktiv.devtools.protocol.ClientInfo
import io.github.syrou.reaktiv.devtools.protocol.ClientRole
import io.github.syrou.reaktiv.introspection.network.NetworkBodyPart
import io.github.syrou.reaktiv.introspection.protocol.CapturedAction
import io.github.syrou.reaktiv.introspection.protocol.DeltaKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@OptIn(ExperimentalReaktivApi::class)
class DevToolsSessionViewTest {

    private val reduce = DevToolsUiModule.reducer

    private val stores = mutableListOf<Store>()
    private val connections = mutableListOf<DevToolsConnection>()

    @AfterTest
    fun tearDown() {
        connections.forEach { runCatching { runBlocking { it.disconnect() } } }
        connections.clear()
        stores.forEach { runCatching { it.cleanup() } }
        stores.clear()
    }

    private fun uiStore(): Store = createStore {
        module(DevToolsUiModule)
        coroutineContext(Dispatchers.Default)
    }.also(stores::add)

    private suspend fun Store.awaitUi(what: String, predicate: (DevToolsUiState) -> Boolean): DevToolsUiState {
        val reached = withTimeoutOrNull(5_000) { selectState<DevToolsUiState>().first(predicate) }
        return assertNotNull(reached, "the ui never reached: $what")
    }

    private fun counter(count: Int, kind: DeltaKind, type: String) = CapturedAction(
        clientId = "device",
        timestamp = count.toLong(),
        actionType = type,
        actionData = "{}",
        stateDeltaJson = "{\"count\":$count}",
        moduleName = "Counter",
        deltaKind = kind
    )

    private fun log(message: String, at: Long) = DeviceLogRow(
        clientId = "device",
        level = "INFO",
        category = "App",
        message = message,
        timestampMs = at
    )

    private fun client(id: String, role: ClientRole, isGhost: Boolean = false) = ClientInfo(
        clientId = id,
        clientName = id,
        platform = "JVM",
        role = role,
        publisherClientId = null,
        connectedAt = 0L,
        isGhost = isGhost
    )

    @Test
    fun `leaving an imported session for a live device ends the ghost and its time travel`() {
        var state = reduce(DevToolsUiState(), DevToolsUiAction.SelectPublisher("ghost-1"))
        state = reduce(state, DevToolsUiAction.EnableTimeTravelWithGhost("ghost-1"))

        state = reduce(state, DevToolsUiAction.SelectPublisher("device"))

        assertNull(state.activeGhostId)
        assertFalse(state.timeTravelEnabled)
    }

    @Test
    fun `selecting the imported session again keeps it`() {
        var state = reduce(DevToolsUiState(), DevToolsUiAction.SelectPublisher("ghost-1"))
        state = reduce(state, DevToolsUiAction.EnableTimeTravelWithGhost("ghost-1"))

        state = reduce(state, DevToolsUiAction.SelectPublisher("ghost-1"))

        assertEquals("ghost-1", state.activeGhostId)
    }

    @Test
    fun `clearing history rebases the baseline onto the state at the clear point`() {
        var state = DevToolsUiState(initialStateJson = """{"Counter":{"count":0}}""")
        state = reduce(state, DevToolsUiAction.AddActionStateEvent(counter(5, DeltaKind.FULL, "SetTo5")))

        state = reduce(state, DevToolsUiAction.ClearHistory)

        val baseline = Json.parseToJsonElement(state.initialStateJson).jsonObject
        assertEquals(5, baseline.getValue("Counter").jsonObject.getValue("count").jsonPrimitive.int)
    }

    @Test
    fun `a history resync does not repeat log lines already shown`() {
        val first = log("started", 1)
        val second = log("loaded", 2)
        var state = reduce(DevToolsUiState(), DevToolsUiAction.AppendDeviceLogs(listOf(first, second)))

        state = reduce(state, DevToolsUiAction.AppendDeviceLogs(listOf(first, second, log("ready", 3))))

        assertEquals(listOf("started", "loaded", "ready"), state.deviceLogs.map { it.message })
    }

    @Test
    fun `a line logged twice in one batch is kept twice`() {
        val retry = log("retrying", 1)

        val state = reduce(DevToolsUiState(), DevToolsUiAction.AppendDeviceLogs(listOf(retry, retry)))

        assertEquals(2, state.deviceLogs.size)
    }

    @Test
    fun `the devtools ui is never counted as a device whatever its role`() {
        val clients = listOf(
            client(DEVTOOLS_UI_CLIENT_ID, ClientRole.UNASSIGNED),
            client("device", ClientRole.PUBLISHER),
            client("follower", ClientRole.LISTENER),
            client("ghost-1", ClientRole.PUBLISHER, isGhost = true)
        )

        assertEquals(listOf("device", "follower"), clients.liveDevices().map { it.clientId })
    }

    @Test
    fun `a live device body can be fetched after an imported session was left`() = runBlocking<Unit> {
        val store = uiStore()
        store.dispatchAndAwait(DevToolsUiAction.SelectPublisher("ghost-1"))
        store.dispatchAndAwait(DevToolsUiAction.EnableTimeTravelWithGhost("ghost-1"))
        store.dispatchAndAwait(DevToolsUiAction.SelectPublisher("device"))

        DevToolsUiModule.selectLogicTyped(store).fetchNetworkBody("device", "req-1", NetworkBodyPart.RESPONSE)

        val key = networkBodyKey("req-1", NetworkBodyPart.RESPONSE)
        val load = store.awaitUi("a body load") { key in it.networkBodies }.networkBodies.getValue(key)
        assertFalse(load.capturedOnly, "a live device was treated as an imported session")
    }

    @Test
    fun `a session file that cannot be read tells the import dialog why`() = runBlocking<Unit> {
        val store = uiStore()

        DevToolsUiModule.selectLogicTyped(store).importGhostSession("{invalid json!!!")

        store.awaitUi("an import error") { it.ghostImportError != null }
    }

    @Test
    fun `a link request fails at once when the device cannot be reached`() = runBlocking<Unit> {
        val store = uiStore()
        val connection = DevToolsConnection("ws://127.0.0.1:1/ws").also(connections::add)
        val logic = DevToolsUiModule.selectLogicTyped(store)
        logic.setConnection(connection)
        store.dispatchAndAwait(DevToolsUiAction.SelectPublisher("device"))

        logic.openLinkOnPublisher("home", "https://example.com/home", emptyMap())

        val attempt = store.awaitUi("a settled link attempt") { state ->
            state.linkAttempts.any { it.status != RequestStatus.PENDING }
        }.linkAttempts.single()
        assertEquals(RequestStatus.ANSWERED, attempt.status)
        assertNotNull(attempt.error)
    }
}
