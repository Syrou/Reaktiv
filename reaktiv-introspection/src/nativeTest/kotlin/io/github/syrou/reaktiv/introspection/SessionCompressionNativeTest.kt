package io.github.syrou.reaktiv.introspection

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The native codec drives zlib through cinterop, where pinning and buffer arithmetic can be wrong
 * in ways the JVM implementation cannot reproduce, so the round trip is asserted on a real native
 * target rather than trusted from the shared tests.
 */
class SessionCompressionNativeTest {

    private fun sessionLikeJson(requests: Int): String = buildString {
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
    fun `round trip returns the original bytes`() = runTest {
        val original = sessionLikeJson(50).encodeToByteArray()

        assertContentEquals(original, gzipDecompress(gzipCompress(original)))
    }

    @Test
    fun `a payload larger than the internal chunk round trips`() = runTest {
        val original = sessionLikeJson(20_000).encodeToByteArray()

        assertTrue(original.size > 64 * 1024, "fixture must exceed one zlib chunk")
        assertContentEquals(original, gzipDecompress(gzipCompress(original)))
    }

    @Test
    fun `empty input round trips`() = runTest {
        assertEquals(0, gzipDecompress(gzipCompress(ByteArray(0))).size)
    }

    @Test
    fun `unicode survives the round trip`() = runTest {
        val original = """{"note":"höger ümlaut 日本語 🎵"}"""

        assertEquals(original, gzipDecompress(gzipCompress(original.encodeToByteArray())).decodeToString())
    }

    @Test
    fun `compressed output carries the gzip magic number`() = runTest {
        assertTrue(isGzip(gzipCompress(sessionLikeJson(5).encodeToByteArray())))
    }
}
