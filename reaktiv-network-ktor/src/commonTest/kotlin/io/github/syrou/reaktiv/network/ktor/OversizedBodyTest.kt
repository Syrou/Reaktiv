package io.github.syrou.reaktiv.network.ktor

import io.github.syrou.reaktiv.introspection.network.NetworkRequestCapture
import io.github.syrou.reaktiv.introspection.network.NetworkEventListener
import io.github.syrou.reaktiv.introspection.network.NetworkTap
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class OversizedBodyTest {

    @AfterTest
    fun cleanup() {
        NetworkTap.clear()
    }

    @Test
    fun `a body past the hard limit is still reported with a bounded preview`() = runTest {
        val captured = mutableListOf<NetworkRequestCapture>()
        val listener = NetworkEventListener { captured.add(it) }
        NetworkTap.addListener(listener)

        val huge = "x".repeat(50_000)
        val engine = MockEngine {
            respond(
                content = huge,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        }
        val client = HttpClient(engine) {
            install(ReaktivNetworkInspection) {
                maxBodyBytes = 256
                hardBodyLimitBytes = 1024
            }
        }

        client.get("https://api.example.com/huge")

        val event = captured.single()
        assertTrue(event.responseBodyTruncated, "A body past the hard limit must be marked truncated")
        val body = assertNotNull(event.responseBody)
        assertEquals(256, body.length, "The preview must be bounded by maxBodyBytes")
        assertTrue(event.responseBodySize >= 50_000, "The reported size must be the real size")

        NetworkTap.removeListener(listener)
        client.close()
    }
}
