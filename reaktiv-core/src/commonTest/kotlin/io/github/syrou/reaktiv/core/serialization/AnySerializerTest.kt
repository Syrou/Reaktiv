package io.github.syrou.reaktiv.core.serialization

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class AnySerializerTest {

    private fun roundTrip(value: Map<String, Any?>): Map<*, *> =
        Json.decodeFromString(AnySerializer, Json.encodeToString(AnySerializer, value)) as Map<*, *>

    @Test
    fun `a fractional number keeps full double precision`() {
        val restored = roundTrip(mapOf("lat" to 59.334591))

        assertEquals(59.334591, restored["lat"])
    }

    @Test
    fun `whole numbers decode as Int when they fit and Long when they do not`() {
        val restored = roundTrip(mapOf("small" to 5L, "big" to 9_000_000_000L))

        assertEquals(5, restored["small"])
        assertEquals(9_000_000_000L, restored["big"])
    }

    @Test
    fun `a null nested in a list or map decodes as null`() {
        val restored = roundTrip(mapOf("list" to listOf("a", null), "map" to mapOf("k" to null)))

        assertEquals(listOf("a", null), restored["list"])
        assertNull((restored["map"] as Map<*, *>)["k"])
    }

    @Test
    fun `a top level json value is written as json like a nested one`() {
        val value = buildJsonObject { put("id", JsonPrimitive(7)) }

        assertEquals("""{"id":7}""", Json.encodeToString(AnySerializer, value))
        assertEquals("5", Json.encodeToString(AnySerializer, JsonPrimitive(5)))
    }

    @Test
    fun `a top level null is reported instead of becoming text`() {
        assertFailsWith<SerializationException> {
            Json.decodeFromString(AnySerializer, "null")
        }
    }
}
