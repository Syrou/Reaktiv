import io.github.syrou.reaktiv.navigation.util.CommonUrlEncoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class CommonUrlEncoderTest {

    private val encoder = CommonUrlEncoder()

    @Test
    fun `test content URI encoding and decoding preserves original encoding`() {
        val originalUri = "content://com.google.android.apps.docs.storage/document/acc%3D9%3Bdoc%3Dencoded%3Dq3kaiKNyhntVpRozJ-eI-R1HG6TXaN_W7vafc216hOscCol3D2WPTL4kvwY%3D"

        // Encode the URI (double-encodes existing %3D parts)
        val encoded = encoder.encodePath(originalUri)

        // Verify that %3D becomes %253D (double-encoded)
        assertTrue(encoded.contains("%253D"))
        assertFalse(encoded.contains("%3D")) // Original %3D should be gone

        // Decode back should restore original URI exactly
        val decoded = encoder.decode(encoded)
        assertEquals(originalUri, decoded)
    }

    @Test
    fun `test basic path encoding`() {
        assertEquals("hello%20world", encoder.encodePath("hello world"))
        assertEquals("test%2Fpath", encoder.encodePath("test/path"))
        assertEquals("caf%C3%A9", encoder.encodePath("café"))
        assertEquals("", encoder.encodePath(""))
    }

    @Test
    fun `test basic query encoding`() {
        assertEquals("hello%20world", encoder.encodeQuery("hello world"))
        assertEquals("name%3Dvalue%26other%3Dtest", encoder.encodeQuery("name=value&other=test"))
        assertEquals("search%2Bterm", encoder.encodeQuery("search+term"))
    }

    @Test
    fun `test basic decoding`() {
        assertEquals("hello world", encoder.decode("hello%20world"))
        assertEquals("test/path", encoder.decode("test%2Fpath"))
        assertEquals("café", encoder.decode("caf%C3%A9"))
        assertEquals("hello world", encoder.decode("hello+world")) // Plus to space
        assertEquals("", encoder.decode(""))
    }

    @Test
    fun `test URI encoding with various special characters`() {
        val uri = "https://example.com/path?param=value%3Dtest&other=%20space"
        val encoded = encoder.encodePath(uri)
        val decoded = encoder.decode(encoded)

        assertEquals(uri, decoded)
        // Verify double encoding occurred
        assertTrue(encoded.contains("%253D")) // %3D became %253D
        assertTrue(encoded.contains("%2520")) // %20 became %2520
    }

    @Test
    fun `test decode stops at appropriate level`() {
        // Should stop when no more encoded sequences are found
        val partiallyEncoded = "hello%20world%3Dvalue"
        val result = encoder.decode(partiallyEncoded)
        assertEquals("hello world=value", result)
    }

    @Test
    fun `test invalid hex sequences are treated as literals`() {
        assertEquals("test%ZZinvalid", encoder.decode("test%ZZinvalid"))
        assertEquals("incomplete%2", encoder.decode("incomplete%2"))
        assertEquals("short%", encoder.decode("short%"))
    }

    @Test
    fun `test safe characters are not encoded`() {
        val safeChars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
        assertEquals(safeChars, encoder.encodePath(safeChars))

        val querySafeChars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~!*'()"
        assertEquals(querySafeChars, encoder.encodeQuery(querySafeChars))
    }

    @Test
    fun `test round trip encoding and decoding`() {
        val testStrings = listOf(
            "simple test",
            "with/special&chars=value",
            "unicode: 🌟 café naïve",
            "already%20encoded%3Dvalue",
            "mixed%20content and new content",
            "https://example.com/path?q=search%20term&type=test"
        )

        testStrings.forEach { original ->
            val pathEncoded = encoder.encodePath(original)
            val pathDecoded = encoder.decode(pathEncoded)
            assertEquals(original, pathDecoded, "Path round-trip failed for: $original")

            val queryEncoded = encoder.encodeQuery(original)
            val queryDecoded = encoder.decode(queryEncoded)
            assertEquals(original, queryDecoded, "Query round-trip failed for: $original")
        }
    }

    @Test
    fun `test URI round trip preserves original encoding`() {
        val urisWithEncoding = listOf(
            "content://provider/document/id%3Dvalue",
            "https://api.com/endpoint?param%3Dtest%26other%3Dvalue",
            "file:///path/to/file%20with%20spaces.txt",
            "custom://scheme/path%2Fwith%2Fencoded%2Fslashes"
        )

        urisWithEncoding.forEach { original ->
            val encoded = encoder.encodePath(original)
            val decoded = encoder.decode(encoded)
            assertEquals(original, decoded, "URI round-trip failed for: $original")
        }
    }

    @Test
    fun `test containsEncodedSequences helper method`() {
        // This tests the private method indirectly through decode
        val withEncoding = "test%20with%3Dencoding"
        val withoutEncoding = "plain text no encoding"
        val withPlusEncoding = "test+with+plus"

        // If containsEncodedSequences works correctly, decode should decode these
        assertNotEquals(withEncoding, encoder.decode(withEncoding))
        assertEquals(withoutEncoding, encoder.decode(withoutEncoding))
        assertNotEquals(withPlusEncoding, encoder.decode(withPlusEncoding))
    }

    @Test
    fun `test edge cases`() {
        // Empty string
        assertEquals("", encoder.encodePath(""))
        assertEquals("", encoder.decode(""))
        assertEquals("", encoder.encodeQuery(""))

        // Single character
        assertEquals("a", encoder.encodePath("a"))
        assertEquals("a", encoder.decode("a"))

        // Only encoded characters
        assertEquals("%20%21%22", encoder.encodePath(" !\""))
        assertEquals(" !\"", encoder.decode("%20%21%22"))

        // Mixed encoded and unencoded
        val mixed = "hello%20world test"
        val encoded = encoder.encodePath(mixed)
        val decoded = encoder.decode(encoded)
        assertEquals(mixed, decoded)
    }
}