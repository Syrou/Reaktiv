package io.github.syrou.reaktiv.devtools.ui

import io.github.syrou.reaktiv.introspection.network.NetworkRequestCapture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NetworkFilteringTest {

    private fun row(
        id: String,
        method: String = "GET",
        url: String = "https://api.example.com/items",
        status: Int? = 200,
        durationMs: Long = 10,
        responseBytes: Long = 100,
        error: String? = null
    ) = NetworkEventRow(
        clientId = "device",
        event = NetworkRequestCapture(
            id = id,
            startedAtMs = id.removePrefix("n").toLong(),
            durationMs = durationMs,
            method = method,
            url = url,
            responseStatus = status,
            responseBodySize = responseBytes,
            error = error
        )
    )

    private val sample = listOf(
        row("n1", method = "GET", url = "https://api.example.com/items", status = 200, durationMs = 10),
        row("n2", method = "POST", url = "https://api.example.com/items", status = 500, durationMs = 90),
        row("n3", method = "GET", url = "https://cdn.example.com/logo.png", status = 200, durationMs = 40),
        row("n4", method = "GET", url = "https://api.example.com/users", status = null, durationMs = 5, error = "timeout")
    )

    @Test
    fun `newest first is the default order`() {
        val result = sample.applyNetworkFilter(NetworkFilter())
        assertEquals(listOf("n4", "n3", "n2", "n1"), result.map { it.event.id })
    }

    @Test
    fun `failures only keeps error and 5xx responses`() {
        val result = sample.applyNetworkFilter(NetworkFilter(failuresOnly = true))
        assertEquals(setOf("n2", "n4"), result.map { it.event.id }.toSet())
    }

    @Test
    fun `method filter is case insensitive on the event side`() {
        val result = sample.applyNetworkFilter(NetworkFilter(methods = setOf("POST")))
        assertEquals(listOf("n2"), result.map { it.event.id })
    }

    @Test
    fun `query matches url, method and status`() {
        assertEquals(
            listOf("n3"),
            sample.applyNetworkFilter(NetworkFilter(query = "cdn")).map { it.event.id }
        )
        assertEquals(
            listOf("n2"),
            sample.applyNetworkFilter(NetworkFilter(query = "500")).map { it.event.id }
        )
        assertEquals(
            listOf("n4"),
            sample.applyNetworkFilter(NetworkFilter(query = "timeout")).map { it.event.id }
        )
    }

    @Test
    fun `slowest first orders by duration`() {
        val result = sample.applyNetworkFilter(NetworkFilter(sort = NetworkSort.SLOWEST))
        assertEquals(listOf("n2", "n3", "n1", "n4"), result.map { it.event.id })
    }

    @Test
    fun `filters combine`() {
        val result = sample.applyNetworkFilter(
            NetworkFilter(methods = setOf("GET"), failuresOnly = true)
        )
        assertEquals(listOf("n4"), result.map { it.event.id })
    }

    @Test
    fun `an unknown response size does not subtract from the total`() {
        val unknown = listOf(row("n1", responseBytes = -1))
        val stats = unknown.endpointStats().single()
        assertEquals(0L, stats.totalBytes, "A -1 sentinel must not become negative bytes")
    }

    @Test
    fun `endpoint stats group by method host and path`() {
        val stats = sample.endpointStats()

        assertEquals(4, stats.size)
        val failing = stats.first()
        assertTrue(failing.failures > 0, "Endpoints with failures sort first")

        val items = stats.single { it.path == "/items" && it.method == "POST" }
        assertEquals("api.example.com", items.host)
        assertEquals(1, items.calls)
        assertEquals(90L, items.slowestMs)
    }

    @Test
    fun `repeated calls to one endpoint collapse into a single row`() {
        val repeated = (1..5).map { row("n$it", url = "https://api.example.com/poll", durationMs = it * 10L) }
        val stats = repeated.endpointStats().single()

        assertEquals(5, stats.calls)
        assertEquals(50L, stats.slowestMs)
        assertEquals(30L, stats.medianMs)
    }

    @Test
    fun `host and path parsing survives urls without a path`() {
        assertEquals("api.example.com", networkHost("https://api.example.com"))
        assertEquals("/", networkPath("https://api.example.com"))
        assertEquals("/a/b", networkPath("https://api.example.com/a/b?q=1"))
    }
}
