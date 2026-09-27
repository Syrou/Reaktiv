package eu.syrou.example.reaktiv.videos

import eu.syrou.example.domain.network.ExampleHttp
import eu.syrou.example.domain.data.VideoItem
import io.github.syrou.reaktiv.core.Module
import io.github.syrou.reaktiv.core.ModuleAction
import io.github.syrou.reaktiv.core.ModuleLogic
import io.github.syrou.reaktiv.core.ModuleState
import io.github.syrou.reaktiv.core.StoreAccessor
import kotlinx.serialization.Serializable

class VideosModule(private val http: ExampleHttp) : Module<VideosModule.VideosState, VideosModule.VideosAction> {
    @Serializable
    data class VideosState(
        val videos: List<VideoItem> = emptyList(),
        val loading: Boolean = true,
    ) : ModuleState

    sealed class VideosAction : ModuleAction(VideosModule::class) {
        data class SetAggregatedVideos(val news: List<VideoItem>) : VideosAction()
        data class NewsLoading(val loading: Boolean) : VideosAction()
    }

    override val initialState = VideosState()
    override val reducer: (VideosState, VideosAction) -> VideosState = { state, action ->
        when (action) {
            is VideosAction.SetAggregatedVideos -> state.copy(videos = action.news)
            is VideosAction.NewsLoading -> state.copy(loading = action.loading)
        }
    }

    override val createLogic: (storeAccessor: StoreAccessor) -> ModuleLogic = { storeAccessor ->
        VideosLogic(storeAccessor, http)
    }
}