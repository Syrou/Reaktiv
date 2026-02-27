package eu.syrou.androidexample.reaktiv.twitchstreams

import eu.syrou.androidexample.domain.network.twitchstream.TwitchApiClient
import eu.syrou.androidexample.reaktiv.news.NewsModule
import io.github.syrou.reaktiv.core.Dispatch
import io.github.syrou.reaktiv.core.ModuleAction
import io.github.syrou.reaktiv.core.ModuleLogic
import io.github.syrou.reaktiv.core.StoreAccessor
import io.github.syrou.reaktiv.core.util.selectState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class TwitchStreamsLogic(private val storeAccessor: StoreAccessor) : ModuleLogic() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        scope.launch {
            val thing = storeAccessor.selectState<NewsModule.NewsState>().first().news
            println(thing)
        }
    }

    private suspend fun fetchPathOfExileStreams(accessToken: String): List<TwitchApiClient.Stream> {
        val twitchApiClient = TwitchApiClient(accessToken)
        try {
            val streams = twitchApiClient.getActivePathOfExileStreams()
            return streams
        } finally {
            twitchApiClient.close()
        }
    }


    override suspend fun invoke(action: ModuleAction) {
        when (action) {
            is TwitchStreamsModule.TwitchStreamsAction.AccessToken -> {
                storeAccessor.dispatch(TwitchStreamsModule.TwitchStreamsAction.NewsLoading(true))
                storeAccessor.dispatch(
                    TwitchStreamsModule.TwitchStreamsAction.SetTwitchStreamers(
                        fetchPathOfExileStreams(
                            action.accessToken
                        )
                    )
                )
                storeAccessor.dispatch(TwitchStreamsModule.TwitchStreamsAction.NewsLoading(true))
            }
        }
    }
}