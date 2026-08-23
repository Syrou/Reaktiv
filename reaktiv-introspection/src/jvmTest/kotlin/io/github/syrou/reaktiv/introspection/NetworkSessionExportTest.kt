package io.github.syrou.reaktiv.introspection

import io.github.syrou.reaktiv.core.util.reaktivJson
import io.github.syrou.reaktiv.introspection.capture.SessionCapture
import io.github.syrou.reaktiv.introspection.network.NetworkBodyPart
import io.github.syrou.reaktiv.introspection.network.NetworkBodyProvider
import io.github.syrou.reaktiv.introspection.network.NetworkRequestCapture
import io.github.syrou.reaktiv.introspection.network.NetworkTap
import io.github.syrou.reaktiv.introspection.network.sliceOnCharBoundary
import io.github.syrou.reaktiv.introspection.protocol.SessionExport
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NetworkSessionExportTest {

    private val json = reaktivJson()

    @AfterTest
    fun tearDown() {
        NetworkTap.clear()
    }

    private fun capture(id: String, url: String, preview: String) = NetworkRequestCapture(
        id = id,
        startedAtMs = 1_000,
        durationMs = 42,
        method = "GET",
        url = url,
        responseStatus = 200,
        responseBody = preview,
        responseBodySize = 4096,
        responseBodyTruncated = true
    )

    @Test
    fun `exported session contains the network exchanges that transpired`() = runTest {
        val session = SessionCapture()
        session.start("client-1", "Test Device", "JVM")

        NetworkTap.emit(capture("req-1", "https://api.example.com/tracks", "{\"a\":1}"))
        NetworkTap.emit(capture("req-2", "https://api.example.com/albums", "{\"b\":2}"))
        session.flush()

        val export = json.decodeFromString<SessionExport>(session.exportSession())

        assertEquals(2, export.session.network.size)
        assertEquals(
            listOf("https://api.example.com/tracks", "https://api.example.com/albums"),
            export.session.network.map { it.url }
        )
        assertEquals(200, export.session.network[0].responseStatus)
        session.stop()
    }

    @Test
    fun `full response body is materialised from the retention window at capture time`() = runTest {
        val fullBody = buildString { repeat(500) { append("payload-$it;") } }
        val provider = NetworkBodyProvider { requestId, part, offset, maxBytes ->
            if (requestId != "req-1" || part != NetworkBodyPart.RESPONSE) null
            else fullBody.encodeToByteArray().sliceOnCharBoundary(offset, maxBytes)
        }
        NetworkTap.addBodyProvider(provider)

        val session = SessionCapture()
        session.start("client-1", "Test Device", "JVM")
        NetworkTap.emit(capture("req-1", "https://api.example.com/tracks", "{\"trunc\":true}"))
        session.flush()

        val export = json.decodeFromString<SessionExport>(session.exportSession())
        val exchange = export.session.network.single()

        assertEquals(fullBody, exchange.responseBody)
        assertFalse(exchange.responseBodyTruncated)
        session.stop()
    }

    @Test
    fun `session history carries network so a late orchestrator sees prior traffic`() = runTest {
        val session = SessionCapture()
        session.start("client-1", "Test Device", "JVM")

        NetworkTap.emit(capture("req-1", "https://api.example.com/tracks", "{}"))
        session.flush()

        val history = session.getSessionHistory()

        assertEquals(1, history.network.size)
        assertEquals("https://api.example.com/tracks", history.network.single().url)
        session.stop()
    }

    @Test
    fun `stopping the capture detaches the listener`() = runTest {
        val session = SessionCapture()
        session.start("client-1", "Test Device", "JVM")
        session.stop()

        assertFalse(NetworkTap.hasListeners)
    }

    @Test
    fun `the capture lane serves bodies the emitting window has already evicted`() = runTest {
        val fullBody = buildString { repeat(400) { append("evicted-payload-$it;") } }
        var windowHoldsBody = true
        val provider = NetworkBodyProvider { requestId, part, offset, maxBytes ->
            if (!windowHoldsBody || requestId != "req-1" || part != NetworkBodyPart.RESPONSE) null
            else fullBody.encodeToByteArray().sliceOnCharBoundary(offset, maxBytes)
        }
        NetworkTap.addBodyProvider(provider)

        val session = SessionCapture()
        session.start("client-1", "Test Device", "JVM")
        NetworkTap.emit(capture("req-1", "https://api.example.com/tracks", "{}"))
        session.flush()

        windowHoldsBody = false

        val slice = NetworkTap.bodySlice("req-1", NetworkBodyPart.RESPONSE, 0, 64)

        assertTrue(slice != null, "the lane should answer once the window has evicted")
        assertEquals(fullBody.take(64), slice.content)
        assertEquals(fullBody.length, slice.totalBytes)
        session.stop()
    }

    @Test
    fun `the lane serves the right body when many exchanges are recorded`() = runTest {
        val bodies = (0 until 30).associate { "req-$it" to "body-$it-${"x".repeat(200)}" }
        val provider = NetworkBodyProvider { requestId, part, offset, maxBytes ->
            if (part != NetworkBodyPart.RESPONSE) null
            else bodies[requestId]?.encodeToByteArray()?.sliceOnCharBoundary(offset, maxBytes)
        }
        NetworkTap.addBodyProvider(provider)

        val session = SessionCapture()
        session.start("client-1", "Test Device", "JVM")
        bodies.keys.forEach { id ->
            NetworkTap.emit(capture(id, "https://api.example.com/$id", "{}"))
        }
        session.flush()
        NetworkTap.removeBodyProvider(provider)

        // Interleave lookups so a cache that tore between key and value would surface here.
        listOf("req-7", "req-21", "req-7", "req-0", "req-29", "req-21").forEach { id ->
            val slice = NetworkTap.bodySlice(id, NetworkBodyPart.RESPONSE, 0, 1024)
            assertTrue(slice != null, "no slice for $id")
            assertTrue(
                slice.content.startsWith("body-${id.removePrefix("req-")}-"),
                "$id resolved to the wrong body: ${slice.content.take(20)}"
            )
        }
        session.stop()
    }

    @Test
    fun `stopping the capture removes the fallback body provider`() = runTest {
        val fullBody = "some-body-content"
        val provider = NetworkBodyProvider { requestId, part, offset, maxBytes ->
            if (requestId != "req-1" || part != NetworkBodyPart.RESPONSE) null
            else fullBody.encodeToByteArray().sliceOnCharBoundary(offset, maxBytes)
        }
        NetworkTap.addBodyProvider(provider)

        val session = SessionCapture()
        session.start("client-1", "Test Device", "JVM")
        NetworkTap.emit(capture("req-1", "https://api.example.com/tracks", "{}"))
        session.flush()
        session.stop()
        NetworkTap.removeBodyProvider(provider)

        assertEquals(null, NetworkTap.bodySlice("req-1", NetworkBodyPart.RESPONSE, 0, 64))
    }

    @Test
    fun `a captured session survives compression and reloads with its network intact`() = runTest {
        val session = SessionCapture()
        session.start("client-1", "Test Device", "JVM")
        repeat(40) { index ->
            NetworkTap.emit(
                capture(
                    "req-$index",
                    "https://api.example.com/v7/tracks/$index",
                    """{"data":[{"id":$index,"title":"a track title"}]}"""
                )
            )
        }
        session.flush()

        val exportJson = session.exportSession()
        val fileBytes = gzipCompress(exportJson.encodeToByteArray())

        assertTrue(isGzip(fileBytes), "the written file should be gzip")
        assertTrue(
            fileBytes.size * 3 < exportJson.length,
            "expected the file to shrink, ${exportJson.length} -> ${fileBytes.size}"
        )

        val reloaded = json.decodeFromString<SessionExport>(decodeSessionBytes(fileBytes))

        assertEquals(40, reloaded.session.network.size)
        assertEquals("https://api.example.com/v7/tracks/0", reloaded.session.network.first().url)
        assertEquals("https://api.example.com/v7/tracks/39", reloaded.session.network.last().url)
        session.stop()
    }

    @Test
    fun `a session with no network traffic exports an empty list rather than failing`() = runTest {
        val session = SessionCapture()
        session.start("client-1", "Test Device", "JVM")
        session.flush()

        val export = json.decodeFromString<SessionExport>(session.exportSession())

        assertTrue(export.session.network.isEmpty())
        session.stop()
    }
}
