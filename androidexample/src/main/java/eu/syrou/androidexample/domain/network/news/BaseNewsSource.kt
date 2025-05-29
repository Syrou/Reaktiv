package eu.syrou.androidexample.domain.network.news

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.DEFAULT
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json

abstract class BaseNewsSource : NewsSource {
    @OptIn(ExperimentalSerializationApi::class)
    protected val client = HttpClient {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                isLenient = true
                explicitNulls = false
            })
        }
        install(Logging){
            /*logger = object: Logger {
                override fun log(message: String) {
                    println(message)
                }
            }*/
            level = LogLevel.ALL
        }
    }

    protected suspend inline fun <reified T> getAndParseJson(url: String): T {
        return client.get(url).body()
    }

    protected suspend fun getAndParseRss(url: String): String {
        return client.get(url).bodyAsText()
    }
}