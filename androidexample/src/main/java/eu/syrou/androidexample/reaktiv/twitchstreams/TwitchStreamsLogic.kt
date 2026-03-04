package eu.syrou.androidexample.reaktiv.twitchstreams

import eu.syrou.androidexample.domain.network.twitchstream.TwitchApiClient
import eu.syrou.androidexample.reaktiv.news.NewsModule
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

    suspend fun loadStreams(accessToken: String) {
        storeAccessor.dispatch(TwitchStreamsModule.TwitchStreamsAction.NewsLoading(true))
        storeAccessor.dispatch(
            TwitchStreamsModule.TwitchStreamsAction.SetTwitchStreamers(
                fetchPathOfExileStreams(accessToken)
            )
        )
        storeAccessor.dispatch(TwitchStreamsModule.TwitchStreamsAction.NewsLoading(false))
    }

    private suspend fun fetchPathOfExileStreams(accessToken: String): List<TwitchApiClient.Stream> {
        val twitchApiClient = TwitchApiClient(accessToken)
        try {
            return twitchApiClient.getActivePathOfExileStreams()
        } finally {
            twitchApiClient.close()
        }
    }
}