package eu.syrou.androidexample.domain.network.twitchstream

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class TwitchApiClient(private val accessToken: String) {
    private val client = HttpClient {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                isLenient = true
            })
        }
    }

    private val clientId = "w1relgmm50jmppc69fqrhh2j6e86om"
    private val baseUrl = "https://api.twitch.tv/helix"
    private val officialChannelName = "pathofexile"

    @Serializable
    data class Stream(
        val id: String,
        val user_name: String,
        val user_login: String? = null,
        val game_name: String,
        val title: String,
        val viewer_count: Int,
        val started_at: String,
        val thumbnail_url: String? = null,
        val tags: List<String>? = emptyList()
    ) {
        fun getTwitchUrl(): String {
            return "https://www.twitch.tv/${user_name}"
        }

        fun getThumbnailOfSize(width: Int, height: Int): String? {
            return thumbnail_url?.replace("{width}", "$width")
                ?.replace("{height}", "$height")
        }
    }

    @Serializable
    data class StreamsResponse(
        val data: List<Stream>,
        val pagination: Map<String, String>
    )

    suspend fun getActivePathOfExileStreams(limit: Int = 100): List<Stream> {
        val officialChannel = getOfficialChannelIfLive()
        val response: StreamsResponse = client.get("$baseUrl/streams") {
            // Path of Exile game ID
            parameter("game_id", "29307")
            parameter("type", "live")
            parameter("first", limit.toString())
            header("Client-ID", clientId)
            header("Authorization", "Bearer $accessToken")
        }.body()
        val sortedStreams = response.data.sortedByDescending { it.viewer_count }

        return if (officialChannel != null) {
            listOf(officialChannel) + sortedStreams.filter { it.user_name.lowercase() != officialChannelName }
        } else {
            sortedStreams
        }
    }

    private suspend fun getOfficialChannelIfLive(): Stream? {
        val response: StreamsResponse = client.get("$baseUrl/streams") {
            parameter("user_login", officialChannelName)
            header("Client-ID", clientId)
            header("Authorization", "Bearer $accessToken")
        }.body()

        return response.data.firstOrNull()
    }

    fun close() {
        client.close()
    }
}