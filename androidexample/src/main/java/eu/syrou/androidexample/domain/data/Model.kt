package eu.syrou.androidexample.domain.data

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

@Serializable
data class VideoItem(
    val title: String,
    val link: String,
    val description: String,
    val pubDate: Instant,
    val channelName: String,
    val thumbnailUrl: String,
    val videoId: String
)
data class StreamItem(val streamer: String, val title: String, val category: String)

@Serializable
data class NewsItem(
    val title: String,
    val link: String,
    val description: String?,
    val pubDate: Instant,
    val source: String,
    val author: String
)