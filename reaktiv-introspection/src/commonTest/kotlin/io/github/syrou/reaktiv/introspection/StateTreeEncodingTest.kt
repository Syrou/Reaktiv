package io.github.syrou.reaktiv.introspection

import io.github.syrou.reaktiv.core.ModuleState
import io.github.syrou.reaktiv.introspection.capture.SessionCapture
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Serializable
data class DefaultsState(val count: Int = 0, val label: String = "none") : ModuleState

@Serializable
data class UnregisteredState(val value: Int = 1) : ModuleState

class StateTreeEncodingTest {

    private val serializers = SerializersModule {
        polymorphic(ModuleState::class) { subclass(DefaultsState::class) }
    }

    @Test
    fun `a state tree carries default values`() {
        val capture = SessionCapture()
        capture.attachStateSerializers(serializers)

        val tree = capture.encodeStateTree(mapOf("defaults" to DefaultsState()))

        val module = tree.modules.getValue("defaults").jsonObject
        assertEquals("0", module["count"]!!.jsonPrimitive.content)
        assertEquals("none", module["label"]!!.jsonPrimitive.content)
        assertTrue(tree.failed.isEmpty())
    }

    @Test
    fun `one module that cannot be encoded leaves the others in the baseline`() = runTest {
        val capture = SessionCapture()
        capture.start("client-tree", "TreeApp", "TestPlatform")
        capture.attachStateSerializers(serializers)

        capture.captureInitialState(mapOf("defaults" to DefaultsState(), "unregistered" to UnregisteredState()))

        val initial = Json.parseToJsonElement(capture.getSessionHistory().initialStateJson).jsonObject
        assertTrue("defaults" in initial)
        assertFalse("unregistered" in initial)
        assertEquals(setOf("unregistered"), capture.encodeStateTree(mapOf("unregistered" to UnregisteredState())).failed.keys)
        capture.stop()
    }
}
