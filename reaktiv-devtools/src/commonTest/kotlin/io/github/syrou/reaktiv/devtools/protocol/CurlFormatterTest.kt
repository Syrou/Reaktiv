package io.github.syrou.reaktiv.devtools.protocol

import io.github.syrou.reaktiv.introspection.network.NetworkRequestCapture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CurlFormatterTest {

    private fun capture(
        method: String = "GET",
        url: String = "https://api.example.com/items",
        headers: Map<String, List<String>> = emptyMap(),
        contentType: String? = null,
        body: String? = null
    ) = NetworkRequestCapture(
        id = "net-1",
        startedAtMs = 0L,
        durationMs = 10L,
        method = method,
        url = url,
        requestHeaders = headers,
        requestContentType = contentType,
        requestBody = body
    )

    @Test
    fun formatsSimpleGet() {
        val curl = CurlFormatter.toCurl(capture())
        assertEquals("curl -X GET 'https://api.example.com/items'", curl)
    }

    @Test
    fun includesHeadersAndBody() {
        val curl = CurlFormatter.toCurl(
            capture(
                method = "POST",
                headers = mapOf("Accept" to listOf("application/json")),
                contentType = "application/json",
                body = """{"name":"widget"}"""
            )
        )
        assertTrue(curl.startsWith("curl -X POST 'https://api.example.com/items'"))
        assertTrue(curl.contains("-H 'Accept: application/json'"))
        assertTrue(curl.contains("-H 'Content-Type: application/json'"))
        assertTrue(curl.contains("--data-raw '{\"name\":\"widget\"}'"))
    }

    @Test
    fun doesNotDuplicateContentTypeHeader() {
        val curl = CurlFormatter.toCurl(
            capture(
                method = "POST",
                headers = mapOf("Content-Type" to listOf("text/plain")),
                contentType = "text/plain",
                body = "hello"
            )
        )
        assertEquals(1, Regex("Content-Type").findAll(curl).count())
    }

    @Test
    fun escapesSingleQuotes() {
        val curl = CurlFormatter.toCurl(capture(body = "it's"))
        assertTrue(curl.contains("--data-raw 'it'\\''s'"))
    }
}
