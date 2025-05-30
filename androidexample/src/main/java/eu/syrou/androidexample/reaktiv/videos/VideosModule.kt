package eu.syrou.androidexample.reaktiv.videos

import eu.syrou.androidexample.domain.data.NewsItem
import eu.syrou.androidexample.domain.data.VideoItem
import io.github.syrou.reaktiv.core.Dispatch
import io.github.syrou.reaktiv.core.Module
import io.github.syrou.reaktiv.core.ModuleAction
import io.github.syrou.reaktiv.core.ModuleLogic
import io.github.syrou.reaktiv.core.ModuleState
import io.github.syrou.reaktiv.core.StoreAccessor
import kotlinx.serialization.Serializable

object VideosModule : Module<VideosModule.VideosState, VideosModule.VideosAction> {
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

    override val createLogic: (storeAccessor: StoreAccessor) -> ModuleLogic<VideosAction> = { storeAccessor ->
        println("HERPA DERPA - Assigning and creating logic for: ${this::class.qualifiedName}")
        VideosLogic(storeAccessor)
    }
}