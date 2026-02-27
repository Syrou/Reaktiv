package eu.syrou.androidexample.reaktiv.settings

import eu.syrou.androidexample.reaktiv.twitchstreams.TwitchStreamsModule
import io.github.syrou.reaktiv.core.Dispatch
import io.github.syrou.reaktiv.core.Module
import io.github.syrou.reaktiv.core.ModuleAction
import io.github.syrou.reaktiv.core.ModuleLogic
import io.github.syrou.reaktiv.core.ModuleState
import io.github.syrou.reaktiv.core.StoreAccessor
import kotlinx.serialization.Serializable

object SettingsModule : Module<SettingsModule.SettingsState, SettingsModule.SettingsAction> {
    @Serializable
    data class SettingsState(
        val drawerOpen: Boolean = false,
        val twitchAccessToken: String? = null,
    ) : ModuleState

    sealed class SettingsAction : ModuleAction(SettingsModule::class) {
        @Serializable
        data class SetDrawerOpen(val open: Boolean) : SettingsAction()
        @Serializable
        data class SetTwitchAccessToken(val accessToken: String?) : SettingsAction()
    }

    override val initialState = SettingsState()
    override val reducer: (SettingsState, SettingsAction) -> SettingsState = { state, action ->
        when (action) {
            is SettingsAction.SetDrawerOpen -> state.copy(drawerOpen = action.open)
            is SettingsAction.SetTwitchAccessToken -> state.copy(twitchAccessToken = action.accessToken)
        }
    }
    override val createLogic: (storeAccessor: StoreAccessor) -> ModuleLogic =
        { storeAccessor -> SettingsLogic(storeAccessor) }
}