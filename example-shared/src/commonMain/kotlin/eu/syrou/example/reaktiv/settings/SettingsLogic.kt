package eu.syrou.example.reaktiv.settings

import eu.syrou.example.reaktiv.twitchstreams.TwitchStreamsLogic
import io.github.syrou.reaktiv.core.ModuleLogic
import io.github.syrou.reaktiv.core.StoreAccessor
import io.github.syrou.reaktiv.core.util.selectLogic
import kotlinx.coroutines.withContext
import eu.syrou.example.ioDispatcher

class SettingsLogic(private val storeAccessor: StoreAccessor) : ModuleLogic() {

    suspend fun setTwitchAccessToken(token: String?) = withContext(ioDispatcher) {
        storeAccessor.dispatch(SettingsModule.SettingsAction.SetTwitchAccessToken(token))
        token?.let {
            storeAccessor.selectLogic<TwitchStreamsLogic>().loadStreams(it)
        }
    }
}
