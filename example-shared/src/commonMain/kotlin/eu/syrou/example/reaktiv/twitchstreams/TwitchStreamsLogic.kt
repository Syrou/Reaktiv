package eu.syrou.example.reaktiv.twitchstreams

import eu.syrou.example.domain.network.ExampleHttp
import eu.syrou.example.domain.network.twitchstream.TwitchApiClient
import eu.syrou.example.ioDispatcher
import io.github.syrou.reaktiv.core.ModuleLogic
import io.github.syrou.reaktiv.core.StoreAccessor
import kotlinx.coroutines.withContext

class TwitchStreamsLogic(private val storeAccessor: StoreAccessor, private val http: ExampleHttp) : ModuleLogic() {

    suspend fun loadStreams(accessToken: String) = withContext(ioDispatcher) {
        storeAccessor.dispatch(TwitchStreamsModule.TwitchStreamsAction.NewsLoading(true))
        try {
            storeAccessor.dispatch(
                TwitchStreamsModule.TwitchStreamsAction.SetTwitchStreamers(
                    TwitchApiClient(http, accessToken).getActivePathOfExileStreams()
                )
            )
        } finally {
            storeAccessor.dispatch(TwitchStreamsModule.TwitchStreamsAction.NewsLoading(false))
        }
    }
}
