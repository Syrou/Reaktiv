package eu.syrou.androidexample.domain.network.video

import eu.syrou.androidexample.domain.data.VideoItem
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

interface VideoSource {
    suspend fun fetchVideos(): List<VideoItem>
}

abstract class BaseVideoSource : VideoSource {
    protected val client = HttpClient {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                isLenient = true
            })
        }
    }

    protected suspend inline fun <reified T> getAndParseJson(url: String): T {
        return client.get(url).body()
    }

    protected suspend fun getAndParseRss(url: String): String {
        return client.get(url).bodyAsText()
    }
}