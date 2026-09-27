package eu.syrou.example.reaktiv.videos

import kotlinx.coroutines.withContext
import eu.syrou.example.domain.network.ExampleHttp
import eu.syrou.example.domain.data.offlineVideos
import eu.syrou.example.domain.network.video.VideosAggregator
import eu.syrou.example.domain.network.video.YouTubeRssSource
import io.github.syrou.reaktiv.core.ModuleLogic
import io.github.syrou.reaktiv.core.StoreAccessor
import eu.syrou.example.ioDispatcher

class VideosLogic(private val storeAccessor: StoreAccessor, http: ExampleHttp) : ModuleLogic() {
    private val videoAggregator = VideosAggregator(
        listOf(
            YouTubeRssSource(
                http,
                "https://www.youtube.com/feeds/videos.xml?channel_id=UCA7X5unt1JrIiVReQDUbl_A",
                "pathofexile"
            ),
            YouTubeRssSource(
                http,
                "https://www.youtube.com/feeds/videos.xml?channel_id=UCAG3CiKOUkQysyKCXSFEBPA",
                "zizaran"
            ),
            YouTubeRssSource(
                http,
                "https://www.youtube.com/feeds/videos.xml?channel_id=UCqAJ4uINwVtBXnKT_DzRwHw",
                "goratha"
            ),
            YouTubeRssSource(
                http,
                "https://www.youtube.com/feeds/videos.xml?channel_id=UCiH4O8e8fwwzZgRKBFAcUzQ",
                "ventrua"
            ),
            YouTubeRssSource(
                http,
                "https://www.youtube.com/feeds/videos.xml?channel_id=UCnaP100kTBB_WGM9IiF73yw",
                "mathil"
            ),
            YouTubeRssSource(
                http,
                "https://www.youtube.com/feeds/videos.xml?channel_id=UCJcwQtZokx7drvLbWecjIbw",
                "havok616"
            ),
            YouTubeRssSource(
                http,
                "https://www.youtube.com/feeds/videos.xml?channel_id=UCXFfqrwNMRSwO9F2hMYWVXQ",
                "steelmage"
            ),
            YouTubeRssSource(
                http,
                "https://www.youtube.com/feeds/videos.xml?channel_id=UCXp5YOW329ysRDl_LK9P1_g",
                "palsteron"
            ),
            YouTubeRssSource(
                http,
                "https://www.youtube.com/feeds/videos.xml?channel_id=UCSDZkgmigfYbdw7hJ-6Wo6A",
                "dslily"
            )
        )
    )

    suspend fun load() = withContext(ioDispatcher) {
        storeAccessor.dispatch(VideosModule.VideosAction.NewsLoading(true))
        try {
            storeAccessor.dispatch(VideosModule.VideosAction.SetAggregatedVideos(videoAggregator.aggregateVideos().ifEmpty { offlineVideos() }))
        } finally {
            storeAccessor.dispatch(VideosModule.VideosAction.NewsLoading(false))
        }
    }
}
