package io.github.syrou.reaktiv.network.ktor

import io.github.syrou.reaktiv.introspection.network.NetworkEventListener
import io.github.syrou.reaktiv.introspection.network.NetworkRequestCapture
import io.github.syrou.reaktiv.introspection.network.NetworkTap
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable

class DecodeFailureTest {

    @Serializable
    data class User(val id: Int, val name: String)

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

    private fun jsonClient(respondWith: String): HttpClient {
        val engine = MockEngine { _ ->
            respond(
                content = respondWith,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        return HttpClient(engine) {
            install(ContentNegotiation) {
                json()
            }
            install(ReaktivNetworkInspection)
        }
    }

    @Test
    fun nullOnNonNullableFieldIsAttributedToTheExchange() = runTest {
        val payload = """{"id":1,"name":null}"""
        val client = jsonClient(payload)

        assertFailsWith<Throwable> {
            client.get("https://api.example.com/user").body<User>()
        }

        assertEquals(1, events.map { it.id }.distinct().size)
        val failed = events.last()
        assertEquals(200, failed.responseStatus)
        assertEquals(payload, failed.responseBody)
        assertNotNull(failed.decodeError)
        assertTrue(failed.isFailure)
    }

    @Test
    fun missingRequiredFieldNamesTheFieldInTheDecodeError() = runTest {
        val client = jsonClient("""{"id":1}""")

        assertFailsWith<Throwable> {
            client.get("https://api.example.com/user").body<User>()
        }

        val decodeError = events.last().decodeError
        assertNotNull(decodeError)
        assertTrue(decodeError.contains("name"), decodeError)
    }

    @Test
    fun repeatedBodyReadsReportTheFailureOnce() = runTest {
        val client = jsonClient("""{"id":1,"name":null}""")
        val response = client.get("https://api.example.com/user")

        assertFailsWith<Throwable> { response.body<User>() }
        assertFailsWith<Throwable> { response.body<User>() }

        assertEquals(1, events.count { it.decodeError != null })
    }

    @Test
    fun successfulDecodeLeavesNoDecodeError() = runTest {
        val client = jsonClient("""{"id":1,"name":"ada"}""")

        val user = client.get("https://api.example.com/user").body<User>()

        assertEquals("ada", user.name)
        assertEquals(1, events.size)
        assertNull(events.single().decodeError)
    }
}
