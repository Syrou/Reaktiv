package eu.syrou.example.domain.network.news

import eu.syrou.example.domain.network.HttpSource
import eu.syrou.example.domain.network.ExampleHttp
import eu.syrou.example.domain.data.NewsItem
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlin.time.Instant
import eu.syrou.example.ioDispatcher

@Serializable
data class RedditApiResponse(
    val data: RedditData
)

@Serializable
data class RedditData(
    val children: List<RedditPost>
)

@Serializable
data class RedditPost(
    val data: RedditPostData
)

@Serializable
data class RedditPostData(
    val title: String,
    val author: String,
    val url: String,
    val score: Int,
    val selftext: String,
    val num_comments: Int,
    val link_flair_text: String,
    val created_utc: Float
)

class PathOfExileRedditSource(http: ExampleHttp, private val url: String) : HttpSource(http), NewsSource {
    override suspend fun fetchNews(): List<NewsItem> = withContext(ioDispatcher) {
        val response: RedditApiResponse = getAndParseJson(url)
        response.data.children
            .map { post ->
                NewsItem(
                    title = post.data.title,
                    link = post.data.url,
                    description = null,
                    pubDate = Instant.fromEpochSeconds(post.data.created_utc.toLong()),
                    source = "/r/pathofexile",
                    author = post.data.author
                )
            }
    }
}