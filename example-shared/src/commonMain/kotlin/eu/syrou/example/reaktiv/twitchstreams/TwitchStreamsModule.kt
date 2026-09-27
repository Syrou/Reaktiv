package eu.syrou.example.reaktiv.twitchstreams

import eu.syrou.example.domain.data.ALL_CATEGORIES
import eu.syrou.example.domain.network.ExampleHttp
import eu.syrou.example.domain.network.twitchstream.TwitchApiClient
import io.github.syrou.reaktiv.core.Module
import io.github.syrou.reaktiv.core.ModuleAction
import io.github.syrou.reaktiv.core.ModuleLogic
import io.github.syrou.reaktiv.core.ModuleState
import io.github.syrou.reaktiv.core.StoreAccessor
import kotlinx.serialization.Serializable

class TwitchStreamsModule(private val http: ExampleHttp) : Module<TwitchStreamsModule.TwitchStreamsState, TwitchStreamsModule.TwitchStreamsAction> {
    @Serializable
    data class TwitchStreamsState(
        val loading: Boolean = false,
        val twitchStreamers: List<TwitchApiClient.Stream> = emptyList(),
        val categoryFilter: String = ALL_CATEGORIES
    ) : ModuleState

    sealed class TwitchStreamsAction : ModuleAction(TwitchStreamsModule::class) {
        data class SetTwitchStreamers(val twitchStreamers: List<TwitchApiClient.Stream>) : TwitchStreamsAction()
        data class NewsLoading(val loading: Boolean) : TwitchStreamsAction()
        data class SetCategoryFilter(val category: String) : TwitchStreamsAction()
    }

    override val initialState = TwitchStreamsState()
    override val reducer: (TwitchStreamsState, TwitchStreamsAction) -> TwitchStreamsState = { state, action ->
        when (action) {
            is TwitchStreamsAction.SetTwitchStreamers -> state.copy(twitchStreamers = action.twitchStreamers)
            is TwitchStreamsAction.NewsLoading -> state.copy(loading = action.loading)
            is TwitchStreamsAction.SetCategoryFilter -> state.copy(categoryFilter = action.category)
        }
    }

    override val createLogic: (storeAccessor: StoreAccessor) -> ModuleLogic = { storeAccessor ->
        TwitchStreamsLogic(storeAccessor, http)
    }
}