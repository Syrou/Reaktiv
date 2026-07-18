package io.github.syrou.reaktiv.introspection

import io.github.syrou.reaktiv.core.ModuleAction
import io.github.syrou.reaktiv.core.ModuleState
import io.github.syrou.reaktiv.core.util.reaktivJson
import io.github.syrou.reaktiv.introspection.capture.SessionCapture
import io.github.syrou.reaktiv.introspection.protocol.CapturedAction
import io.github.syrou.reaktiv.introspection.protocol.DeltaKind
import io.github.syrou.reaktiv.introspection.protocol.StateReconstructor
import io.github.syrou.reaktiv.introspection.protocol.mergeCapturedDeltas
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Serializable
data class DeltaTestState(
    val count: Int = 0,
    val label: String = "default",
    val flag: Boolean = true
) : ModuleState

class DeltaSyncTest {

    private val serializers = SerializersModule {
        polymorphic(ModuleState::class) {
            subclass(DeltaTestState::class)
        }
    }

    private fun action() = object : ModuleAction(DeltaTestState::class) {}

    @Test
    fun `first capture is full then changed fields only`() = runTest {
        val capture = SessionCapture()
        capture.start("c", "DeltaApp", "JVM")
        capture.attachStateSerializers(serializers)

        capture.captureDispatchedAction(action(), DeltaTestState(count = 1, label = "a"))
        capture.captureDispatchedAction(action(), DeltaTestState(count = 2, label = "a"))
        capture.flush()

        val history = capture.getSessionHistory()
        assertEquals(DeltaKind.FULL, history.actions[0].deltaKind)
        assertEquals(DeltaKind.FIELDS, history.actions[1].deltaKind)

        val delta = reaktivJson().parseToJsonElement(history.actions[1].stateDeltaJson).jsonObject
        assertTrue("count" in delta)
        assertTrue("type" in delta)
        assertFalse("label" in delta)
        assertFalse("flag" in delta)
        capture.stop()
    }

    @Test
    fun `field reverting to its default value is captured in the delta`() = runTest {
        val capture = SessionCapture()
        capture.start("c", "DeltaApp", "JVM")
        capture.attachStateSerializers(serializers)

        capture.captureDispatchedAction(action(), DeltaTestState(flag = false))
        capture.captureDispatchedAction(action(), DeltaTestState(flag = true))
        capture.flush()

        val history = capture.getSessionHistory()
        val delta = reaktivJson().parseToJsonElement(history.actions[1].stateDeltaJson).jsonObject
        assertEquals("true", delta["flag"]?.jsonPrimitive?.content)
        capture.stop()
    }

    @Test
    fun `reconstruction over a delta chain equals the final full state`() = runTest {
        val capture = SessionCapture()
        capture.start("c", "DeltaApp", "JVM")
        capture.attachStateSerializers(serializers)

        capture.captureInitialState(mapOf(DeltaTestState::class.qualifiedName!! to DeltaTestState()))
        capture.captureDispatchedAction(action(), DeltaTestState(count = 1))
        capture.captureDispatchedAction(action(), DeltaTestState(count = 1, label = "x"))
        capture.captureDispatchedAction(action(), DeltaTestState(count = 5, label = "x", flag = false))
        capture.flush()

        val history = capture.getSessionHistory()
        val reconstructed = StateReconstructor.reconstructAtIndex(
            history.initialStateJson, history.actions, history.actions.size - 1
        )
        val module = reaktivJson().parseToJsonElement(reconstructed).jsonObject[DeltaTestState::class.qualifiedName!!]!!.jsonObject
        assertEquals(5, module["count"]?.jsonPrimitive?.content?.toInt())
        assertEquals("x", module["label"]?.jsonPrimitive?.content)
        assertEquals("false", module["flag"]?.jsonPrimitive?.content)
        capture.stop()
    }

    @Test
    fun `merge conflation keeps latest values across pending deltas`() {
        val pending = CapturedAction(
            "c", 1L, "A", "", """{"type":"T","count":1,"label":"a"}""", "M", DeltaKind.FIELDS
        )
        val incoming = CapturedAction(
            "c", 2L, "B", "", """{"type":"T","count":2}""", "M", DeltaKind.FIELDS
        )
        val merged = mergeCapturedDeltas(pending, incoming)
        val obj = reaktivJson().parseToJsonElement(merged.stateDeltaJson).jsonObject
        assertEquals(DeltaKind.FIELDS, merged.deltaKind)
        assertEquals(2, obj["count"]?.jsonPrimitive?.content?.toInt())
        assertEquals("a", obj["label"]?.jsonPrimitive?.content)

        val fullIncoming = incoming.copy(deltaKind = DeltaKind.FULL)
        assertEquals(fullIncoming, mergeCapturedDeltas(pending, fullIncoming))

        val fullPending = pending.copy(deltaKind = DeltaKind.FULL)
        val promoted = mergeCapturedDeltas(fullPending, incoming)
        assertEquals(DeltaKind.FULL, promoted.deltaKind)
        val promotedObj = reaktivJson().parseToJsonElement(promoted.stateDeltaJson).jsonObject
        assertEquals("a", promotedObj["label"]?.jsonPrimitive?.content)
        assertEquals(2, promotedObj["count"]?.jsonPrimitive?.content?.toInt())
    }
}
