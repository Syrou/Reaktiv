package eu.syrou.example.reaktiv.news

import eu.syrou.example.domain.data.offlineNews
import eu.syrou.example.domain.network.ExampleHttp
import eu.syrou.example.domain.network.news.JsonNewsSource
import eu.syrou.example.domain.network.news.NewsAggregator
import eu.syrou.example.domain.network.news.PathOfExileRedditSource
import eu.syrou.example.domain.network.news.RssNewsSource
import eu.syrou.example.ioDispatcher
import io.github.syrou.reaktiv.core.ModuleLogic
import io.github.syrou.reaktiv.core.StoreAccessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class NewsLogic(private val storeAccessor: StoreAccessor, http: ExampleHttp) : ModuleLogic() {
    private val newsAggregator = NewsAggregator(
        listOf(
            RssNewsSource(http, "https://www.pathofexile.com/news/rss", "pathofexile.com"),
            JsonNewsSource(http, "https://maxroll.gg/poe/category/news?_data=custom-routes/game/category", "maxroll.gg"),
            PathOfExileRedditSource(
                http,
                """https://www.reddit.com/r/pathofexile/search.json?q=author:"Community_Team"&restrict_sr=on&sort=new&t=all"""
            )
        )
    )

    suspend fun load() = withContext(ioDispatcher) {
        storeAccessor.dispatch(NewsModule.NewsAction.NewsLoading(true))
        try {
            storeAccessor.dispatch(NewsModule.NewsAction.SetAggregatedNews(newsAggregator.aggregateNews().ifEmpty { offlineNews() }))
        } finally {
            storeAccessor.dispatch(NewsModule.NewsAction.NewsLoading(false))
        }
    }

    suspend fun countDown() = withContext(Dispatchers.Default) {
        (10 downTo 0).forEach {
            delay(1000)
        }
    }
}
