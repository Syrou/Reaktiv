package io.github.syrou.reaktiv.introspection

import io.github.syrou.reaktiv.core.ModuleAction
import io.github.syrou.reaktiv.core.ModuleState
import io.github.syrou.reaktiv.core.util.reaktivJson
import io.github.syrou.reaktiv.introspection.capture.SessionCapture
import io.github.syrou.reaktiv.introspection.protocol.DeltaKind
import io.github.syrou.reaktiv.introspection.protocol.SessionExport
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Serializable
data class FlipVaultState(
    val secretLevel: ClearanceLevel = ClearanceLevel.HIGH,
    val count: Int = 0
) : ModuleState

class TypeSafeRedactionCaptureTest {

    private val json = reaktivJson(encodeDefaults = true)

    private val testSerializers = SerializersModule {
        polymorphic(ModuleState::class) {
            subclass(FlipVaultState::class)
        }
    }

    private val moduleName = FlipVaultState::class.qualifiedName
        ?: FlipVaultState::class.simpleName!!

    private object Poke : ModuleAction(FlipVaultState::class)

    @Test
    fun `a flipping sensitive enum produces no spurious deltas and a masked baseline`() = runTest {
        val capture = SessionCapture()
        capture.start("client-ts", "TypeSafeApp", "TestPlatform")
        capture.attachStateSerializers(testSerializers)

        capture.captureInitialState(mapOf(moduleName to FlipVaultState()))
        capture.captureDispatchedAction(Poke, FlipVaultState(secretLevel = ClearanceLevel.HIGH))
        capture.captureDispatchedAction(Poke, FlipVaultState(secretLevel = ClearanceLevel.LOW))
        capture.captureDispatchedAction(Poke, FlipVaultState(secretLevel = ClearanceLevel.LOW, count = 1))

        val export = json.decodeFromString<SessionExport>(capture.exportSession())

        val initial = json.parseToJsonElement(export.session.initialStateJson).jsonObject
        val initialModule = initial.getValue(moduleName).jsonObject
        assertEquals(REDACTED_PLACEHOLDER, initialModule["secretLevel"]!!.jsonPrimitive.content)

        val actions = export.session.actions
        assertEquals(3, actions.size)
        actions.forEach { assertEquals(DeltaKind.FIELDS, it.deltaKind) }

        val flipDelta = json.parseToJsonElement(actions[1].stateDeltaJson).jsonObject
        assertTrue("secretLevel" !in flipDelta)

        val countDelta = json.parseToJsonElement(actions[2].stateDeltaJson).jsonObject
        assertTrue("count" in countDelta)
        assertTrue("secretLevel" !in countDelta)

        capture.stop()
    }

    @Test
    fun `disabling redactSensitiveKeys captures raw values`() = runTest {
        val capture = SessionCapture(redactSensitiveKeys = false)
        capture.start("client-raw", "RawApp", "TestPlatform")
        capture.attachStateSerializers(testSerializers)

        capture.captureInitialState(mapOf(moduleName to FlipVaultState()))

        val export = json.decodeFromString<SessionExport>(capture.exportSession())
        val initial = json.parseToJsonElement(export.session.initialStateJson).jsonObject
        val initialModule = initial.getValue(moduleName).jsonObject
        assertEquals("HIGH", initialModule["secretLevel"]!!.jsonPrimitive.content)

        capture.stop()
    }
}
