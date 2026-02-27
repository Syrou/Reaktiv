package eu.syrou.androidexample.reaktiv.news

import eu.syrou.androidexample.domain.data.NewsItem
import io.github.syrou.reaktiv.core.Dispatch
import io.github.syrou.reaktiv.core.Module
import io.github.syrou.reaktiv.core.ModuleAction
import io.github.syrou.reaktiv.core.ModuleLogic
import io.github.syrou.reaktiv.core.ModuleState
import io.github.syrou.reaktiv.core.StoreAccessor
import kotlinx.serialization.Serializable

object NewsModule : Module<NewsModule.NewsState, NewsModule.NewsAction> {
    @Serializable
    data class NewsState(
        val news: List<NewsItem> = emptyList(),
        val loading: Boolean = true,
    ) : ModuleState

    sealed class NewsAction : ModuleAction(NewsModule::class) {
        data class SetAggregatedNews(val news: List<NewsItem>) : NewsAction()
        data class NewsLoading(val loading: Boolean) : NewsAction()
    }

    override val initialState = NewsState()
    override val reducer: (NewsState, NewsAction) -> NewsState = { state, action ->
        when (action) {
            is NewsAction.SetAggregatedNews -> state.copy(news = action.news)
            is NewsAction.NewsLoading -> state.copy(loading = action.loading)
        }
    }
    override val createLogic: (storeAccessor: StoreAccessor) -> ModuleLogic = { storeAccessor ->
        println("HERPA DERPA - Assigning and creating logic for: ${this::class.qualifiedName}")
        NewsLogic(storeAccessor = storeAccessor)
    }
}