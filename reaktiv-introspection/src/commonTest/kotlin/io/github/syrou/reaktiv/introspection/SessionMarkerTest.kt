package io.github.syrou.reaktiv.introspection

import io.github.syrou.reaktiv.core.ModuleAction
import io.github.syrou.reaktiv.core.ModuleState
import io.github.syrou.reaktiv.core.util.reaktivJson
import io.github.syrou.reaktiv.introspection.capture.SessionCapture
import io.github.syrou.reaktiv.introspection.protocol.SessionExportFormat
import io.github.syrou.reaktiv.introspection.protocol.SessionExport
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Serializable
data class MarkerTestState(val count: Int = 0) : ModuleState

class SessionMarkerTest {

    private val json = reaktivJson(encodeDefaults = true)

    private val testSerializers = SerializersModule {
        polymorphic(ModuleState::class) {
            subclass(MarkerTestState::class)
        }
    }

    private object Poke : ModuleAction(MarkerTestState::class)

    @Test
    fun `a marker is enriched with the action index and survives export`() = runTest {
        val capture = SessionCapture()
        capture.start("client-m", "MarkerApp", "TestPlatform")
        capture.attachStateSerializers(testSerializers)

        capture.captureDispatchedAction(Poke, MarkerTestState(count = 1))
        capture.captureDispatchedAction(Poke, MarkerTestState(count = 2))
        capture.addMarker("saw the glitch", "list jumped to top")

        val export = json.decodeFromString<SessionExport>(capture.exportSession())

        assertEquals(SessionExportFormat.VERSION, export.version)
        val marker = export.session.markers.single()
        assertEquals("saw the glitch", marker.label)
        assertEquals("list jumped to top", marker.note)
        assertEquals(1, marker.afterActionIndex)
        assertEquals("device", marker.source)
        assertTrue(marker.id.isNotBlank())

        val history = capture.getSessionHistory()
        assertEquals(1, history.markers.size)

        capture.stop()
    }

    @Test
    fun `a historical marker keeps its explicit position and time`() = runTest {
        val capture = SessionCapture()
        capture.start("client-h", "MarkerApp", "TestPlatform")
        capture.attachStateSerializers(testSerializers)

        capture.captureDispatchedAction(Poke, MarkerTestState(count = 1))
        capture.captureDispatchedAction(Poke, MarkerTestState(count = 2))
        capture.captureDispatchedAction(Poke, MarkerTestState(count = 3))
        capture.addMarker(
            label = "spotted later",
            source = "remote",
            timestampMs = 1234L,
            afterActionIndex = 1
        )

        val export = json.decodeFromString<SessionExport>(capture.exportSession())

        val marker = export.session.markers.single()
        assertEquals(1234L, marker.timestampMs)
        assertEquals(1, marker.afterActionIndex)
        assertEquals("remote", marker.source)
        assertEquals(null, marker.route)

        capture.stop()
    }
}
