package io.github.syrou.reaktiv.devtools.ui

import io.github.syrou.reaktiv.core.util.reaktivJson
import io.github.syrou.reaktiv.introspection.protocol.SessionExport
import io.github.syrou.reaktiv.introspection.network.NetworkBodyPart
import io.github.syrou.reaktiv.introspection.protocol.SessionMarker
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GhostMarkerTest {

    private val reduce = DevToolsUiModule.reducer

    private val json = reaktivJson()

    private fun marker(id: String, label: String, source: String) = SessionMarker(
        id = id,
        label = label,
        note = "",
        timestampMs = 1_000,
        source = source
    )

    @Test
    fun `an analyst marker added to a ghost lands in state`() {
        val state = reduce(
            DevToolsUiState(),
            DevToolsUiAction.AddMarker(marker("m1", "checkout hang", "analyst"))
        )

        assertEquals(1, state.markers.size)
        assertEquals("analyst", state.markers.single().source)
    }

    @Test
    fun `replacing a marker keeps its id and position`() {
        val withMarkers = listOf(
            marker("m1", "first", "device"),
            marker("m2", "second", "analyst"),
            marker("m3", "third", "device")
        ).fold(DevToolsUiState()) { state, m ->
            reduce(state, DevToolsUiAction.AddMarker(m))
        }

        val updated = reduce(
            withMarkers,
            DevToolsUiAction.ReplaceMarker(
                withMarkers.markers[1].copy(label = "root cause found", note = "stale token")
            )
        )

        assertEquals(3, updated.markers.size)
        assertEquals(listOf("m1", "m2", "m3"), updated.markers.map { it.id })
        assertEquals("root cause found", updated.markers[1].label)
        assertEquals("stale token", updated.markers[1].note)
    }

    @Test
    fun `replacing an unknown marker leaves the markers untouched`() {
        val before = reduce(
            DevToolsUiState(),
            DevToolsUiAction.AddMarker(marker("m1", "first", "analyst"))
        )

        val after = reduce(before, DevToolsUiAction.ReplaceMarker(marker("nope", "ignored", "analyst")))

        assertEquals(before.markers, after.markers)
    }

    @Test
    fun `imported markers are restored rather than dropped`() {
        val imported = listOf(
            marker("m1", "device marker", "device"),
            marker("m2", "analyst note", "analyst")
        )

        val state = reduce(DevToolsUiState(), DevToolsUiAction.SetMarkers(imported))

        assertEquals(2, state.markers.size)
        assertTrue(state.markers.any { it.source == "analyst" })
    }

    @Test
    fun `markers survive an export round trip so annotations can be re-shared`() {
        val original = json.decodeFromString<SessionExport>(
            """
            {
              "version": "3.6",
              "sessionId": "s1",
              "exportedAt": 1,
              "clientInfo": { "clientId": "c", "clientName": "n", "platform": "p" },
              "session": {
                "startTime": 0,
                "endTime": 1,
                "actions": [],
                "logicStartedEvents": [],
                "logicCompletedEvents": [],
                "logicFailedEvents": [],
                "markers": [
                  { "id": "m1", "label": "device marker", "timestampMs": 5, "source": "device" },
                  { "id": "m2", "label": "analyst note", "timestampMs": 9, "source": "analyst" }
                ]
              }
            }
            """.trimIndent()
        )

        val reloaded = json.decodeFromString<SessionExport>(json.encodeToString(original))

        assertEquals(2, reloaded.session.markers.size)
        assertEquals("analyst", reloaded.session.markers.first { it.id == "m2" }.source)
        assertEquals("analyst note", reloaded.session.markers.first { it.id == "m2" }.label)
    }
}

class GhostNetworkBodyTest {

    private val reduce = DevToolsUiModule.reducer

    @Test
    fun `an imported session closes the body load instead of waiting forever`() {
        val state = reduce(
            DevToolsUiState(),
            DevToolsUiAction.NetworkBodyNotFetchable("req-1", NetworkBodyPart.RESPONSE)
        )

        val load = state.networkBodies[networkBodyKey("req-1", NetworkBodyPart.RESPONSE)]

        assertTrue(load != null)
        assertFalse(load.loading, "a ghost has no device, so nothing is in flight")
        assertTrue(load.complete)
        assertTrue(load.capturedOnly)
    }

    @Test
    fun `a closed load renders the captured preview as a json tree`() {
        val load = NetworkBodyLoad(loading = false, complete = true, unavailable = true, capturedOnly = true)

        val view = resolveBodyView(
            preview = """{"data":[{"id":1}]}""",
            load = load,
            truncated = true,
            contentType = "application/json"
        )

        assertEquals(BodyRender.TREE, view.render)
        assertTrue(view.treeAvailable)
        assertFalse(view.streaming)
        assertEquals("""{"data":[{"id":1}]}""", view.text)
        assertTrue(view.note!!.contains("did not capture the full body"))
    }

    @Test
    fun `a live device that dropped the body keeps its own wording`() {
        val load = NetworkBodyLoad(loading = false, complete = true, unavailable = true)

        val view = resolveBodyView(
            preview = """{"a":1}""",
            load = load,
            truncated = true,
            contentType = "application/json"
        )

        assertTrue(view.note!!.contains("no longer retained on the device"))
    }
}
