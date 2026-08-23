package io.github.syrou.reaktiv.introspection

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Runs on every target, including wasmJs, where the codec is implemented in the host through
 * CompressionStream and btoa rather than in Kotlin. The bridge between that and a suspend function
 * is the part these assertions exist to prove.
 */
class SessionPayloadCommonTest {

    private fun sessionJson(requests: Int): String = buildString {
        append("""{"version":"3.6","session":{"network":[""")
        repeat(requests) { index ->
            if (index > 0) append(',')
            append(
                """{"id":"req-$index","method":"GET",""" +
                    """"url":"https://api.example.com/v7/tracks/$index",""" +
                    """"responseStatus":200,"responseBody":"{\"data\":[{\"id\":$index}]}"}"""
            )
        }
        append("]}}")
    }

    @Test
    fun `payload round trips on this platform`() = runTest {
        val json = sessionJson(25)

        assertEquals(json, decodeSessionPayload(encodeSessionPayload(json)))
    }

    @Test
    fun `encoded payload is not plain json and is smaller`() = runTest {
        val json = sessionJson(300)

        val encoded = encodeSessionPayload(json)

        assertFalse(isPlainSessionJson(encoded))
        assertTrue(
            encoded.length * 3 < json.length,
            "expected the encoded payload to shrink, ${json.length} -> ${encoded.length}"
        )
    }

    @Test
    fun `plain json passes through untouched`() = runTest {
        val json = sessionJson(2)

        assertTrue(isPlainSessionJson(json))
        assertEquals(json, decodeSessionPayload(json))
    }

    @Test
    fun `unicode survives the round trip`() = runTest {
        val json = """{"note":"höger ümlaut 日本語 🎵"}"""

        assertEquals(json, decodeSessionPayload(encodeSessionPayload(json)))
    }

    @Test
    fun `a large payload crossing internal chunk boundaries round trips`() = runTest {
        val json = sessionJson(5_000)

        assertTrue(json.length > 200_000, "fixture should be large")
        assertEquals(json, decodeSessionPayload(encodeSessionPayload(json)))
    }
}
