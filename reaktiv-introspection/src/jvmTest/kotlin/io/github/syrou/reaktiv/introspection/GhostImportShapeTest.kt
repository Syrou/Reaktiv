package io.github.syrou.reaktiv.introspection

import io.github.syrou.reaktiv.core.ModuleAction
import io.github.syrou.reaktiv.core.ModuleState
import io.github.syrou.reaktiv.core.util.reaktivJson
import io.github.syrou.reaktiv.introspection.capture.SessionCapture
import io.github.syrou.reaktiv.introspection.protocol.SessionExport
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class GhostImportShapeTest {

    private val json = reaktivJson()

    private val testSerializers = SerializersModule {
        polymorphic(ModuleState::class) {
            subclass(CounterState::class)
        }
    }

    @Serializable
    data class CounterState(val count: Int = 0) : ModuleState

    object CounterModule
    data class Increment(val amount: Int) : ModuleAction(CounterModule::class)

    @Test
    fun `a device export still carries what the ghost viewer needs after gzip`() = runTest {
        val capture = SessionCapture()
        capture.start("device-1", "Pixel", "Android 14")
        capture.attachStateSerializers(testSerializers)
        capture.captureInitialState(mapOf("counter" to CounterState(0)))
        repeat(5) { index ->
            capture.captureDispatchedAction(Increment(index), CounterState(index + 1))
        }
        capture.flush()

        val exportJson = capture.exportSession()
        val fileBytes = gzipCompress(exportJson.encodeToByteArray())
        val reloaded = json.decodeFromString<SessionExport>(decodeSessionBytes(fileBytes))

        assertNotEquals(
            "{}",
            reloaded.session.initialStateJson,
            "initialStateJson drives the state viewer and must survive the round trip"
        )
        assertTrue(
            reloaded.session.actions.isNotEmpty(),
            "the viewer selects an action index, so an empty action list leaves nothing selected"
        )
        assertEquals(5, reloaded.session.actions.size)
        capture.stop()
    }
}
