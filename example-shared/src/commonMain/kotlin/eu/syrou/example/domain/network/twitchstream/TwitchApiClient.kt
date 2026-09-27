package eu.syrou.example.domain.network.twitchstream

import eu.syrou.example.domain.network.ExampleHttp
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import kotlinx.serialization.Serializable

const val TWITCH_CLIENT_ID: String = "w1relgmm50jmppc69fqrhh2j6e86om"

class TwitchApiClient(private val http: ExampleHttp, private val accessToken: String) {
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
        val response: StreamsResponse = http.client.get("$baseUrl/streams") {
            // Path of Exile game ID
            parameter("game_id", "29307")
            parameter("type", "live")
            parameter("first", limit.toString())
            header("Client-ID", TWITCH_CLIENT_ID)
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
        val response: StreamsResponse = http.client.get("$baseUrl/streams") {
            parameter("user_login", officialChannelName)
            header("Client-ID", TWITCH_CLIENT_ID)
            header("Authorization", "Bearer $accessToken")
        }.body()

        return response.data.firstOrNull()
    }
}