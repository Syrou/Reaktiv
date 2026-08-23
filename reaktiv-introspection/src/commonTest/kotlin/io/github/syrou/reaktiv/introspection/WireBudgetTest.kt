package io.github.syrou.reaktiv.introspection

import io.github.syrou.reaktiv.introspection.capture.SessionHistory
import io.github.syrou.reaktiv.introspection.capture.chunked
import io.github.syrou.reaktiv.introspection.network.NetworkRequestCapture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WireBudgetTest {

    private fun exchange(index: Int, bodyChars: Int) = NetworkRequestCapture(
        id = "req-$index",
        startedAtMs = index.toLong(),
        durationMs = 10,
        method = "GET",
        url = "https://api.example.com/$index",
        responseStatus = 200,
        responseBody = "x".repeat(bodyChars)
    )

    private fun history(exchanges: List<NetworkRequestCapture>) = SessionHistory(
        startTime = 0,
        actions = emptyList(),
        logicStarted = emptyList(),
        logicCompleted = emptyList(),
        logicFailed = emptyList(),
        network = exchanges
    )

    @Test
    fun `body size dominates the estimate`() {
        val small = exchange(1, 10)
        val large = exchange(1, 500_000)

        assertTrue(large.approximateWireBytes() > small.approximateWireBytes() * 100)
        assertTrue(large.approximateWireBytes() >= 500_000)
    }

    @Test
    fun `heavy exchanges cut a chunk long before the count limit`() {
        val heavy = List(10) { exchange(it, 400_000) }

        val chunks = history(heavy).chunked(networkPerChunk = 50)

        assertTrue(
            chunks.size >= 4,
            "10 exchanges of 400KB must not ride in one chunk, got ${chunks.size}"
        )
        chunks.forEach { chunk ->
            val bytes = chunk.network.sumOf { it.approximateWireBytes() }
            assertTrue(
                chunk.network.size <= 1 || bytes <= WireBudget.MAX_PAYLOAD_BYTES,
                "chunk of ${chunk.network.size} exchanges weighs $bytes"
            )
        }
    }

    @Test
    fun `light exchanges still fill up to the count limit`() {
        val light = List(120) { exchange(it, 20) }

        val chunks = history(light).chunked(networkPerChunk = 50)

        assertEquals(3, chunks.size, "120 tiny exchanges at 50 per chunk should be 3 chunks")
        assertEquals(50, chunks[0].network.size)
    }

    @Test
    fun `nothing is lost or reordered regardless of where the cuts land`() {
        val mixed = List(40) { exchange(it, if (it % 5 == 0) 300_000 else 50) }

        val chunks = history(mixed).chunked(networkPerChunk = 50)

        assertEquals(mixed, chunks.flatMap { it.network })
    }

    @Test
    fun `an exchange larger than the whole budget still gets through`() {
        val huge = listOf(exchange(1, WireBudget.MAX_PAYLOAD_BYTES * 2))

        val chunks = history(huge).chunked()

        assertEquals(1, chunks.size)
        assertEquals(1, chunks.single().network.size)
    }
}
