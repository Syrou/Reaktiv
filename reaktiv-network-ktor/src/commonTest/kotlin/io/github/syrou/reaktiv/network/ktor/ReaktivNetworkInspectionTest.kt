package io.github.syrou.reaktiv.network.ktor

import io.github.syrou.reaktiv.introspection.network.NetworkEventListener
import io.github.syrou.reaktiv.introspection.network.NetworkRequestCapture
import io.github.syrou.reaktiv.introspection.network.NetworkTap
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.headersOf
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class ReaktivNetworkInspectionTest {

    private val events = mutableListOf<NetworkRequestCapture>()
    private val listener = NetworkEventListener { events.add(it) }

    @BeforeTest
    fun setUp() {
        NetworkTap.clear()
        events.clear()
        NetworkTap.addListener(listener)
    }

    @AfterTest
    fun tearDown() {
        NetworkTap.clear()
    }

    private fun jsonClient(
        configure: ReaktivNetworkInspectionConfig.() -> Unit = {},
        respondWith: String = """{"ok":true}"""
    ): HttpClient {
        val engine = MockEngine { _ ->
            respond(
                content = respondWith,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        return HttpClient(engine) {
            install(ReaktivNetworkInspection) {
                configure()
            }
        }
    }

    @Test
    fun capturesMethodUrlStatusAndBodies() = runTest {
        val client = jsonClient()
        val response = client.post("https://api.example.com/items?page=2") {
            contentType(ContentType.Application.Json)
            setBody("""{"name":"widget"}""")
        }
        assertEquals("""{"ok":true}""", response.bodyAsText())

        assertEquals(1, events.size)
        val event = events.single()
        assertEquals("POST", event.method)
        assertEquals("https://api.example.com/items?page=2", event.url)
        assertEquals(200, event.responseStatus)
        assertEquals("""{"name":"widget"}""", event.requestBody)
        assertEquals("""{"ok":true}""", event.responseBody)
        assertTrue(event.durationMs >= 0)
    }

    @Test
    fun redactsSensitiveHeaders() = runTest {
        val client = jsonClient()
        client.get("https://api.example.com/me") {
            header(HttpHeaders.Authorization, "Bearer secret-token")
            header("X-Custom", "visible")
        }

        val event = events.single()
        assertEquals(listOf("<redacted>"), event.requestHeaders["Authorization"])
        assertEquals(listOf("visible"), event.requestHeaders["X-Custom"])
    }

    @Test
    fun truncatesOversizedRequestBody() = runTest {
        val client = jsonClient(configure = { maxBodyBytes = 16 })
        client.post("https://api.example.com/upload") {
            contentType(ContentType.Text.Plain)
            setBody("a".repeat(100))
        }

        val event = events.single()
        assertTrue(event.requestBodyTruncated)
        assertEquals(16, event.requestBody?.length)
        assertEquals(100L, event.requestBodySize)
    }

    @Test
    fun skipsNonTextualResponseBody() = runTest {
        val engine = MockEngine { _ ->
            respond(
                content = ByteArray(32) { it.toByte() }.decodeToString(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/octet-stream")
            )
        }
        val client = HttpClient(engine) {
            install(ReaktivNetworkInspection)
        }
        client.get("https://api.example.com/blob")

        val event = events.single()
        assertEquals(null, event.responseBody)
    }

    @Test
    fun capturesEngineFailureAsError() = runTest {
        val engine = MockEngine { _ ->
            throw IllegalStateException("connection refused")
        }
        val client = HttpClient(engine) {
            install(ReaktivNetworkInspection)
        }
        assertFailsWith<IllegalStateException> {
            client.get("https://api.example.com/down")
        }

        val event = events.single()
        assertEquals("connection refused", event.error)
        assertEquals(null, event.responseStatus)
        assertTrue(event.isFailure)
    }

    @Test
    fun doesNothingWithoutListeners() = runTest {
        NetworkTap.clear()
        val client = jsonClient()
        client.get("https://api.example.com/quiet")
        assertTrue(events.isEmpty())
    }
}
