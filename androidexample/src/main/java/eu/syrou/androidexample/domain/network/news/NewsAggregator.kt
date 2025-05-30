package eu.syrou.androidexample.domain.network.news

import eu.syrou.androidexample.domain.data.NewsItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class NewsAggregator(private val sources: List<NewsSource>) {
    suspend fun aggregateNews(): List<NewsItem> = withContext(Dispatchers.Default) {
        sources.flatMap { it.fetchNews() }.sortedByDescending { it.pubDate }
    }
}