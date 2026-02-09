package eu.syrou.androidexample.reaktiv.news

import eu.syrou.androidexample.domain.network.news.JsonNewsSource
import eu.syrou.androidexample.domain.network.news.NewsAggregator
import eu.syrou.androidexample.domain.network.news.PathOfExileRedditSource
import eu.syrou.androidexample.domain.network.news.RssNewsSource
import eu.syrou.androidexample.reaktiv.settings.SettingsModule
import io.github.syrou.reaktiv.core.Dispatch
import io.github.syrou.reaktiv.core.ModuleAction
import io.github.syrou.reaktiv.core.ModuleLogic
import io.github.syrou.reaktiv.core.StoreAccessor
import io.github.syrou.reaktiv.core.util.selectState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.time.delay
import kotlinx.coroutines.withContext

class NewsLogic(storeAccessor: StoreAccessor) : ModuleLogic<NewsModule.NewsAction>() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val rssNewsSource = RssNewsSource("https://www.pathofexile.com/news/rss", "pathofexile.com")
    val maxrollSource =
        JsonNewsSource("https://maxroll.gg/poe/category/news?_data=custom-routes/game/category", "maxroll.gg")
    val pathOfExileSubReddit =
        PathOfExileRedditSource("""https://www.reddit.com/r/pathofexile/search.json?q=author:"Community_Team"&restrict_sr=on&sort=new&t=all""")
    private val newsAggregator = NewsAggregator(listOf(rssNewsSource, maxrollSource, pathOfExileSubReddit))

    init {
        scope.launch {
            val thing = storeAccessor.selectState<SettingsModule.SettingsState>().first()
            println("HERPA DERPA - THING: $thing")
            storeAccessor.dispatch(NewsModule.NewsAction.NewsLoading(true))
            storeAccessor.dispatch(NewsModule.NewsAction.SetAggregatedNews(newsAggregator.aggregateNews()))
            storeAccessor.dispatch(NewsModule.NewsAction.NewsLoading(true))
        }
    }

    suspend fun countDown() = withContext(Dispatchers.Default){
        (10 downTo 0).forEach {
            println("HERPADERPA - Count down: $it")
            delay(1000)
        }
    }
}