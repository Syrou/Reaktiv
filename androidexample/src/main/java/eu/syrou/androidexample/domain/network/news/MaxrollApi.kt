package eu.syrou.androidexample.domain.network.news

import eu.syrou.androidexample.domain.data.NewsItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlin.time.Instant

class JsonNewsSource(private val url: String, private val sourceName: String) : BaseNewsSource() {
    @Serializable
    private data class NewsPost(
        val post_title: String,
        val excerpt: String,
        val post_date_unix: Long,
        val post_author: Author,
        val permalink: String
    )

    @Serializable
    private data class Author(
        val display_name: String
    )

    @Serializable
    private data class NewsResponse(
        val posts: List<NewsPost>? = null,
        val news: List<NewsPost>? = null
    )

    override suspend fun fetchNews(): List<NewsItem> = withContext(Dispatchers.IO) {
        val response: NewsResponse = getAndParseJson(url)
        response.news?.map { post ->
            NewsItem(
                title = post.post_title,
                link = post.permalink,
                description = post.excerpt,
                pubDate = Instant.fromEpochSeconds(post.post_date_unix),
                source = sourceName,
                author = post.post_author.display_name
            )
        }?: emptyList()
    }
}