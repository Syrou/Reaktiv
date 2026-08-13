package io.github.syrou.reaktiv.devtools.ui

import io.github.syrou.reaktiv.introspection.network.NetworkRequestCapture
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HarExportTest {

    private fun row(
        id: String,
        startedAtMs: Long = 1_700_000_000_000,
        url: String = "https://api.example.com/items?page=2&sort=new"
    ) = NetworkEventRow(
        clientId = "device",
        event = NetworkRequestCapture(
            id = id,
            startedAtMs = startedAtMs,
            durationMs = 120,
            method = "POST",
            url = url,
            requestHeaders = mapOf("Content-Type" to listOf("application/json")),
            requestContentType = "application/json",
            requestBody = "{\"a\":1}",
            requestBodySize = 7,
            responseStatus = 201,
            responseStatusText = "Created",
            responseHeaders = mapOf("Content-Type" to listOf("application/json")),
            responseContentType = "application/json",
            responseBody = "{\"ok\":true}",
            responseBodySize = 11,
            waitMs = 100,
            downloadMs = 20
        )
    )

    @Test
    fun `iso timestamps are rendered in utc`() {
        val expected = mapOf(
            0L to "1970-01-01T00:00:00Z",
            1_000L to "1970-01-01T00:00:01Z",
            1_700_000_000_000L to "2023-11-14T22:13:20Z",
            951_782_400_123L to "2000-02-29T00:00:00.123Z",
            1_709_164_800_000L to "2024-02-29T00:00:00Z"
        )
        expected.forEach { (millis, iso) ->
            assertEquals(iso, epochMillisToIso8601(millis), "Mismatch for $millis")
        }
    }

    @Test
    fun `leap days land on the 29th`() {
        assertTrue(epochMillisToIso8601(1_709_164_800_000L).startsWith("2024-02-29"))
        assertTrue(epochMillisToIso8601(951_782_400_123L).startsWith("2000-02-29"))
    }

    @Test
    fun `har carries request and response detail`() {
        val har = listOf(row("n1")).toHar()
        val entry = har["log"]!!.jsonObject["entries"]!!.jsonArray.single().jsonObject

        assertEquals("POST", entry["request"]!!.jsonObject["method"]!!.jsonPrimitive.content)
        assertEquals(201, entry["response"]!!.jsonObject["status"]!!.jsonPrimitive.content.toInt())
        assertEquals(
            "{\"ok\":true}",
            entry["response"]!!.jsonObject["content"]!!.jsonObject["text"]!!.jsonPrimitive.content
        )
        assertEquals(
            "{\"a\":1}",
            entry["request"]!!.jsonObject["postData"]!!.jsonObject["text"]!!.jsonPrimitive.content
        )

        val query = entry["request"]!!.jsonObject["queryString"]!!.jsonArray
        assertEquals(2, query.size)
        assertEquals("page", query[0].jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals("2", query[0].jsonObject["value"]!!.jsonPrimitive.content)

        val timings = entry["timings"]!!.jsonObject
        assertEquals(100, timings["wait"]!!.jsonPrimitive.content.toInt())
        assertEquals(20, timings["receive"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `entries are ordered oldest first and stamped individually`() {
        val har = listOf(
            row("late", startedAtMs = 1_700_000_005_000),
            row("early", startedAtMs = 1_700_000_000_000)
        ).toHar()
        val entries = har["log"]!!.jsonObject["entries"]!!.jsonArray

        val first = entries[0].jsonObject["startedDateTime"]!!.jsonPrimitive.content
        val second = entries[1].jsonObject["startedDateTime"]!!.jsonPrimitive.content
        assertTrue(first < second, "HAR entries must be chronological, got $first then $second")
    }

    @Test
    fun `a url with no query yields an empty query list`() {
        val har = listOf(row("n1", url = "https://api.example.com/items")).toHar()
        val entry = har["log"]!!.jsonObject["entries"]!!.jsonArray.single().jsonObject
        assertEquals(0, entry["request"]!!.jsonObject["queryString"]!!.jsonArray.size)
    }
}
