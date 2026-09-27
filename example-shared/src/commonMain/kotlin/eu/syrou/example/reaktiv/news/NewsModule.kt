package eu.syrou.example.reaktiv.news

import eu.syrou.example.domain.data.ALL_CATEGORIES
import eu.syrou.example.domain.data.NewsItem
import eu.syrou.example.domain.network.ExampleHttp
import io.github.syrou.reaktiv.core.Module
import io.github.syrou.reaktiv.core.ModuleAction
import io.github.syrou.reaktiv.core.ModuleLogic
import io.github.syrou.reaktiv.core.ModuleState
import io.github.syrou.reaktiv.core.StoreAccessor
import kotlinx.serialization.Serializable

class NewsModule(private val http: ExampleHttp) : Module<NewsModule.NewsState, NewsModule.NewsAction> {
    @Serializable
    data class NewsState(
        val news: List<NewsItem> = emptyList(),
        val loading: Boolean = true,
        val categoryFilter: String = ALL_CATEGORIES,
    ) : ModuleState

    sealed class NewsAction : ModuleAction(NewsModule::class) {
        data class SetAggregatedNews(val news: List<NewsItem>) : NewsAction()
        data class NewsLoading(val loading: Boolean) : NewsAction()
        data class SetCategoryFilter(val category: String) : NewsAction()
    }

    override val initialState = NewsState()
    override val reducer: (NewsState, NewsAction) -> NewsState = { state, action ->
        when (action) {
            is NewsAction.SetAggregatedNews -> state.copy(news = action.news)
            is NewsAction.NewsLoading -> state.copy(loading = action.loading)
            is NewsAction.SetCategoryFilter -> state.copy(categoryFilter = action.category)
        }
    }
    override val createLogic: (storeAccessor: StoreAccessor) -> ModuleLogic = { storeAccessor ->
        NewsLogic(storeAccessor, http)
    }
}
