package eu.syrou.androidexample.tooling

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable

@Serializable
data class Subscriber(
    val id: Int,
    val displayName: String,
    val email: String
)

object NetworkFailureProbe {

    private const val NULL_ON_NON_NULLABLE =
        """{"id":42,"displayName":null,"email":"ada@example.com"}"""

    private const val MISSING_REQUIRED_FIELD =
        """{"id":42,"email":"ada@example.com"}"""

    private const val VALID =
        """{"id":42,"displayName":"Ada Lovelace","email":"ada@example.com"}"""

    private val client = HttpClient(
        MockEngine { request ->
            val payload = when {
                request.url.encodedPath.endsWith("/incomplete") -> MISSING_REQUIRED_FIELD
                request.url.encodedPath.endsWith("/valid") -> VALID
                else -> NULL_ON_NON_NULLABLE
            }
            respond(
                content = payload,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
    ) {
        attachNetworkInspection()
        install(ContentNegotiation) {
            json()
        }
    }

    suspend fun fetchSubscriberWithNullName(): Subscriber = fetch("nulled")

    suspend fun fetchIncompleteSubscriber(): Subscriber = fetch("incomplete")

    suspend fun fetchValidSubscriber(): Subscriber = fetch("valid")

    private suspend fun fetch(variant: String): Subscriber =
        client.get("https://api.example.com/v1/subscribers/42/$variant") {
            header(HttpHeaders.Accept, "application/json")
        }.body()
}
