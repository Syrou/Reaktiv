package eu.syrou.androidexample.reaktiv.settings

import eu.syrou.androidexample.reaktiv.twitchstreams.TwitchStreamsModule
import io.github.syrou.reaktiv.core.ModuleLogic
import io.github.syrou.reaktiv.core.StoreAccessor

class SettingsLogic(private val storeAccessor: StoreAccessor) : ModuleLogic() {

    suspend fun setTwitchAccessToken(token: String?) {
        storeAccessor.dispatch(SettingsModule.SettingsAction.SetTwitchAccessToken(token))
        token?.let {
            storeAccessor.dispatch(TwitchStreamsModule.TwitchStreamsAction.AccessToken(it))
        }
    }
}
