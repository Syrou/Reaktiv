package eu.syrou.example.domain.network

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.call.body
import io.ktor.client.plugins.UserAgent
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

class ExampleHttp(configure: HttpClientConfig<*>.() -> Unit = {}) {
    val client: HttpClient = HttpClient {
        configure()
        expectSuccess = true
        install(UserAgent) {
            agent = "android:eu.syrou.example:v1.0 (Reaktiv example app)"
        }
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                isLenient = true
                explicitNulls = false
            })
        }
    }
}

abstract class HttpSource(protected val http: ExampleHttp) {
    protected suspend inline fun <reified T> getAndParseJson(url: String): T = http.client.get(url).body()

    protected suspend fun getAndParseRss(url: String): String = http.client.get(url).bodyAsText()
}
