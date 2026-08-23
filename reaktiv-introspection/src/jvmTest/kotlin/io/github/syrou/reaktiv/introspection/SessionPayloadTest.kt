package io.github.syrou.reaktiv.introspection

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SessionPayloadTest {

    @Test
    fun `payload round trips through the wire encoding`() = runTest {
        val json = sessionLikeJson(30)

        assertEquals(json, decodeSessionPayload(encodeSessionPayload(json)))
    }

    @Test
    fun `encoded payload is base64 and much smaller than the json`() = runTest {
        val json = sessionLikeJson(500)

        val encoded = encodeSessionPayload(json)

        assertFalse(isPlainSessionJson(encoded))
        assertTrue(
            encoded.length * 3 < json.length,
            "expected the encoded payload to shrink, ${json.length} -> ${encoded.length}"
        )
        assertTrue(encoded.all { it.isLetterOrDigit() || it == '+' || it == '/' || it == '=' })
    }

    @Test
    fun `plain json payloads pass through so older clients keep working`() = runTest {
        val json = sessionLikeJson(3)

        assertTrue(isPlainSessionJson(json))
        assertEquals(json, decodeSessionPayload(json))
    }

    @Test
    fun `leading whitespace does not hide plain json`() = runTest {
        val json = "  \n" + sessionLikeJson(2)

        assertTrue(isPlainSessionJson(json))
        assertEquals(json, decodeSessionPayload(json))
    }

    @Test
    fun `unicode survives the wire encoding`() = runTest {
        val json = """{"note":"höger ümlaut 日本語 🎵"}"""

        assertEquals(json, decodeSessionPayload(encodeSessionPayload(json)))
    }
}
