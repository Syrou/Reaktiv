package eu.syrou.androidexample.domain.network.news

import eu.syrou.androidexample.domain.data.NewsItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

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

class PathOfExileRedditSource(private val url: String) : BaseNewsSource() {
    override suspend fun fetchNews(): List<NewsItem> = withContext(Dispatchers.IO) {
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