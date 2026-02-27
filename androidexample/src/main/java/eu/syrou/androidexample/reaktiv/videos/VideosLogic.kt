package eu.syrou.androidexample.reaktiv.videos

import eu.syrou.androidexample.domain.network.video.VideosAggregator
import eu.syrou.androidexample.domain.network.video.YouTubeRssSource
import io.github.syrou.reaktiv.core.Dispatch
import io.github.syrou.reaktiv.core.ModuleAction
import io.github.syrou.reaktiv.core.ModuleLogic
import io.github.syrou.reaktiv.core.StoreAccessor
import io.github.syrou.reaktiv.core.util.selectState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

class VideosLogic(val storeAccessor: StoreAccessor) : ModuleLogic() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val videoAggregator = VideosAggregator(
        listOf(
            YouTubeRssSource(
                "https://www.youtube.com/feeds/videos.xml?channel_id=UCA7X5unt1JrIiVReQDUbl_A",
                "pathofexile"
            ),
            YouTubeRssSource(
                "https://www.youtube.com/feeds/videos.xml?channel_id=UCAG3CiKOUkQysyKCXSFEBPA",
                "zizaran"
            ),
            YouTubeRssSource(
                "https://www.youtube.com/feeds/videos.xml?channel_id=UCqAJ4uINwVtBXnKT_DzRwHw",
                "goratha"
            ),
            YouTubeRssSource(
                "https://www.youtube.com/feeds/videos.xml?channel_id=UCiH4O8e8fwwzZgRKBFAcUzQ",
                "ventrua"
            ),
            YouTubeRssSource(
                "https://www.youtube.com/feeds/videos.xml?channel_id=UCnaP100kTBB_WGM9IiF73yw",
                "mathil"
            ),
            YouTubeRssSource(
                "https://www.youtube.com/feeds/videos.xml?channel_id=UCJcwQtZokx7drvLbWecjIbw",
                "havok616"
            ),
            YouTubeRssSource(
                "https://www.youtube.com/feeds/videos.xml?channel_id=UCXFfqrwNMRSwO9F2hMYWVXQ",
                "steelmage"
            ),
            YouTubeRssSource(
                "https://www.youtube.com/feeds/videos.xml?channel_id=UCXp5YOW329ysRDl_LK9P1_g",
                "palsteron"
            ),
            YouTubeRssSource(
                "https://www.youtube.com/feeds/videos.xml?channel_id=UCSDZkgmigfYbdw7hJ-6Wo6A",
                "dslily"
            )
        )
    )

    init {
        scope.launch {
            storeAccessor.dispatch(VideosModule.VideosAction.NewsLoading(true))
            storeAccessor.dispatch(VideosModule.VideosAction.SetAggregatedVideos(videoAggregator.aggregateVideos()))
            storeAccessor.dispatch(VideosModule.VideosAction.NewsLoading(true))
        }
        scope.launch {
            storeAccessor.selectState<VideosModule.VideosState>().collectLatest {
                println("TESTOR - HEHE: ${it.videos.size}")
            }
        }
    }

    override suspend fun invoke(action: ModuleAction) {

    }
}