package eu.syrou.example.domain.network.news

import io.github.syrou.reaktiv.core.util.ReaktivDebug
import eu.syrou.example.domain.data.NewsItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

class NewsAggregator(private val sources: List<NewsSource>) {
    suspend fun aggregateNews(): List<NewsItem> = withContext(Dispatchers.Default) {
        sources.flatMap { source ->
            try {
                source.fetchNews()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                ReaktivDebug.warn("NewsAggregator: News source ${source::class.simpleName} failed, skipping - ${e.message}")
                emptyList()
            }
        }.sortedByDescending { it.pubDate }
    }
}
