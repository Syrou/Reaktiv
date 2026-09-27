package eu.syrou.example.domain.data

import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

actual fun offlineNews(): List<NewsItem> {
    val now = Clock.System.now()
    return listOf(
        NewsItem(
            title = "Sample news: browsers block the live feeds this app reads on Android",
            link = "https://www.pathofexile.com/forum/view-forum/news",
            description = "The news sources do not allow requests from other sites, so the web example shows these samples instead.",
            pubDate = now - 2.hours,
            source = "sample",
            author = "Reaktiv"
        ),
        NewsItem(
            title = "Sample news: every screen here has an address you can share",
            link = "https://example.com",
            description = "Open a screen, copy the address bar, and paste it into a new tab.",
            pubDate = now - 1.days,
            source = "sample",
            author = "Reaktiv"
        ),
        NewsItem(
            title = "Sample news: back, forward and reload restore the exact stack",
            link = "https://example.com",
            description = "Params such as numbers and objects come back with the history entry.",
            pubDate = now - 2.days,
            source = "sample",
            author = "Reaktiv"
        )
    )
}

actual fun offlineVideos(): List<VideoItem> {
    val now = Clock.System.now()
    return listOf(
        "VR8mKvRgK9A" to "Path of Exile 2 - Official Version 1.0 Trailer",
        "zXLzXS4I2ak" to "Path of Exile 2: The Third Edict Official Trailer",
        "hCwUoaYYdyw" to "Path of Exile 2: Dawn of the Hunt - Official Gameplay Trailer",
        "vxgYGGFNs98" to "Path of Exile 2: Return of the Ancients Official Trailer"
    ).mapIndexed { index, (videoId, title) ->
        VideoItem(
            title = title,
            link = "https://www.youtube.com/watch?v=$videoId",
            description = "The video feeds cannot be read from a browser, so the web example lists these trailers.",
            pubDate = now - (index + 1).days,
            channelName = "Path of Exile",
            thumbnailUrl = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg",
            videoId = videoId
        )
    }
}
