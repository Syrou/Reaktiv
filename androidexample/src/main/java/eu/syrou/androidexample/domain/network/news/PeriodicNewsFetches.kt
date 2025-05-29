package eu.syrou.androidexample.domain.network.news

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import eu.syrou.androidexample.domain.logic.NotificationHelper
import eu.syrou.androidexample.reaktiv.news.NewsModule
import io.github.syrou.reaktiv.core.Store
import kotlinx.coroutines.flow.firstOrNull

class PeriodicNewsFetches(
    appContext: Context,
    params: WorkerParameters,
    val store: Store,
    val notificationHelper: NotificationHelper
) : CoroutineWorker(appContext, params) {

    private val rssNewsSource = RssNewsSource("https://www.pathofexile.com/news/rss", "pathofexile.com")
    private val maxrollSource =
        JsonNewsSource("https://maxroll.gg/poe/category/news?_data=custom-routes/game/category", "maxroll.gg")
    private val pathOfExileSubReddit =
        PathOfExileRedditSource("""https://www.reddit.com/r/pathofexile/search.json?q=author:"Community_Team"&restrict_sr=on&sort=new&t=all""")
    private val newsAggregator = NewsAggregator(
        listOf(
            rssNewsSource,
            maxrollSource,
            pathOfExileSubReddit
        )
    )

    override suspend fun doWork(): Result {
        store.dispatch.invoke(NewsModule.NewsAction.NewsLoading(true))
        val fetchedNews = newsAggregator.aggregateNews()
        val oldNews = store.selectState<NewsModule.NewsState>().firstOrNull()?.news
        if (store.hasPersistedState() && oldNews?.isEmpty() == true) {
            store.loadState()
        }
        if (oldNews?.isNotEmpty() == true && fetchedNews.isNotEmpty()) {
            val previousNewsState = store.selectState<NewsModule.NewsState>().value
            val previousFirstNews = previousNewsState.news.firstOrNull()
            if (previousFirstNews?.title != fetchedNews.firstOrNull()?.title) {
                notificationHelper.showNewsNotification(fetchedNews.first())
            }
            store.dispatch.invoke(NewsModule.NewsAction.SetAggregatedNews(fetchedNews))

            store.dispatch.invoke(NewsModule.NewsAction.NewsLoading(false))
            return Result.success()
        }
        store.dispatch.invoke(NewsModule.NewsAction.NewsLoading(false))
        return Result.failure()

    }
}