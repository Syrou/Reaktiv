package io.github.syrou.reaktiv.introspection

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SessionCompressionTest {

    @Test
    fun `round trip returns the original bytes`() = runTest {
        val original = sessionLikeJson(50).encodeToByteArray()

        val restored = gzipDecompress(gzipCompress(original))

        assertContentEquals(original, restored)
    }

    @Test
    fun `compressed output carries the gzip magic number`() = runTest {
        assertTrue(isGzip(gzipCompress("hello".encodeToByteArray())))
    }

    @Test
    fun `session shaped json compresses substantially`() = runTest {
        val original = sessionLikeJson(500).encodeToByteArray()

        val compressed = gzipCompress(original)

        assertTrue(
            compressed.size * 5 < original.size,
            "expected at least 5x, got ${original.size} -> ${compressed.size}"
        )
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
    fun `plain json is not mistaken for gzip`() {
        assertFalse(isGzip("""{"version":"3.6"}""".encodeToByteArray()))
        assertFalse(isGzip(ByteArray(0)))
        assertFalse(isGzip(ByteArray(1)))
    }

    @Test
    fun `decodeSessionBytes accepts both compressed and plain exports`() = runTest {
        val json = sessionLikeJson(3)

        assertEquals(json, decodeSessionBytes(gzipCompress(json.encodeToByteArray())))
        assertEquals(json, decodeSessionBytes(json.encodeToByteArray()))
    }

    @Test
    fun `decompressing something that is not gzip fails loudly`() = runTest {
        val error = runCatching { gzipDecompress("""{"not":"gzip"}""".encodeToByteArray()) }

        assertTrue(error.isFailure)
    }
}

internal fun sessionLikeJson(requests: Int): String = buildString {
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
