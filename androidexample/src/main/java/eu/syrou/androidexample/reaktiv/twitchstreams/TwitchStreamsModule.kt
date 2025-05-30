package eu.syrou.androidexample.reaktiv.twitchstreams

import eu.syrou.androidexample.domain.network.twitchstream.TwitchApiClient
import io.github.syrou.reaktiv.core.Dispatch
import io.github.syrou.reaktiv.core.Module
import io.github.syrou.reaktiv.core.ModuleAction
import io.github.syrou.reaktiv.core.ModuleLogic
import io.github.syrou.reaktiv.core.ModuleState
import io.github.syrou.reaktiv.core.StoreAccessor
import kotlinx.serialization.Serializable

object TwitchStreamsModule : Module<TwitchStreamsModule.TwitchStreamsState, TwitchStreamsModule.TwitchStreamsAction> {
    @Serializable
    data class TwitchStreamsState(
        val loading: Boolean = false,
        val twitchStreamers: List<TwitchApiClient.Stream> = emptyList()
    ) : ModuleState

    sealed class TwitchStreamsAction : ModuleAction(TwitchStreamsModule::class) {
        data class AccessToken(val accessToken: String) : TwitchStreamsAction()
        data class SetTwitchStreamers(val twitchStreamers: List<TwitchApiClient.Stream>) : TwitchStreamsAction()
        data class NewsLoading(val loading: Boolean) : TwitchStreamsAction()
    }

    override val initialState = TwitchStreamsState()
    override val reducer: (TwitchStreamsState, TwitchStreamsAction) -> TwitchStreamsState = { state, action ->
        when (action) {
            is TwitchStreamsAction.SetTwitchStreamers -> state.copy(twitchStreamers = action.twitchStreamers)
            is TwitchStreamsAction.NewsLoading -> state.copy(loading = action.loading)
            else -> state
        }
    }

    override val createLogic: (storeAccessor: StoreAccessor) -> ModuleLogic<TwitchStreamsAction> = { storeAccessor ->
        println("HERPA DERPA - Assigning and creating logic for: ${this::class.qualifiedName}")
        TwitchStreamsLogic(storeAccessor)
    }
}