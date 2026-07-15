package eu.syrou.androidexample.domain.network.news

import android.util.Log
import eu.syrou.androidexample.domain.data.NewsItem
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
            } catch (e: Exception) {
                Log.w("NewsAggregator", "News source ${source::class.simpleName} failed, skipping", e)
                emptyList()
            }
        }.sortedByDescending { it.pubDate }
    }
}
