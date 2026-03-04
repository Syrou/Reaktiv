package eu.syrou.androidexample.reaktiv.settings

import eu.syrou.androidexample.reaktiv.twitchstreams.TwitchStreamsLogic
import io.github.syrou.reaktiv.core.ModuleLogic
import io.github.syrou.reaktiv.core.StoreAccessor
import io.github.syrou.reaktiv.core.util.selectLogic

class SettingsLogic(private val storeAccessor: StoreAccessor) : ModuleLogic() {

    suspend fun setTwitchAccessToken(token: String?) {
        storeAccessor.dispatch(SettingsModule.SettingsAction.SetTwitchAccessToken(token))
        token?.let {
            storeAccessor.selectLogic<TwitchStreamsLogic>().loadStreams(it)
        }
    }
}
