package io.github.syrou.reaktiv.introspection

import io.github.syrou.reaktiv.introspection.network.sliceOnCharBoundary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NetworkBodySliceTest {

    @Test
    fun `slicing reassembles a multi byte body without corrupting characters`() {
        val original = buildString {
            repeat(500) { append("räksmörgås-😀-$it ") }
        }
        val bytes = original.encodeToByteArray()

        val assembled = StringBuilder()
        var offset = 0
        var slices = 0
        while (true) {
            val slice = bytes.sliceOnCharBoundary(offset, 64)
            assembled.append(slice.content)
            slices++
            assertFalse(
                slice.content.contains('�'),
                "A slice cut a multi byte character in half"
            )
            if (slice.isLast) break
            assertTrue(slice.nextOffset > offset, "Every slice must advance")
            offset = slice.nextOffset
        }

        assertEquals(original, assembled.toString())
        assertTrue(slices > 1, "This body must need more than one slice")
        assertEquals(bytes.size, bytes.sliceOnCharBoundary(0, 64).totalBytes)
    }

    @Test
    fun `a slice larger than the body returns everything at once`() {
        val bytes = """{"ok":true}""".encodeToByteArray()
        val slice = bytes.sliceOnCharBoundary(0, 1024)

        assertEquals("""{"ok":true}""", slice.content)
        assertTrue(slice.isLast)
        assertEquals(bytes.size, slice.nextOffset)
    }

    @Test
    fun `an offset at the end yields an empty final slice`() {
        val bytes = "abc".encodeToByteArray()
        val slice = bytes.sliceOnCharBoundary(3, 64)

        assertEquals("", slice.content)
        assertTrue(slice.isLast)
    }
}
