package eu.syrou.androidexample.reaktiv.lifecycledemo

import io.github.syrou.reaktiv.core.Module
import io.github.syrou.reaktiv.core.ModuleAction
import io.github.syrou.reaktiv.core.ModuleLogic
import io.github.syrou.reaktiv.core.ModuleState
import io.github.syrou.reaktiv.core.StoreAccessor
import kotlinx.serialization.Serializable

object LifecycleDemoModule : Module<LifecycleDemoModule.LifecycleDemoState, LifecycleDemoModule.LifecycleDemoAction> {
    @Serializable
    data class LifecycleDemoState(
        val name: String = "",
        val email: String = "",
        val notes: String = "",
        val timesCleared: Int = 0
    ) : ModuleState

    sealed class LifecycleDemoAction : ModuleAction(LifecycleDemoModule::class) {
        @Serializable
        data class SetName(val value: String) : LifecycleDemoAction()

        @Serializable
        data class SetEmail(val value: String) : LifecycleDemoAction()

        @Serializable
        data class SetNotes(val value: String) : LifecycleDemoAction()

        @Serializable
        data object ClearFields : LifecycleDemoAction()
    }

    override val initialState = LifecycleDemoState()
    override val reducer: (LifecycleDemoState, LifecycleDemoAction) -> LifecycleDemoState = { state, action ->
        when (action) {
            is LifecycleDemoAction.SetName -> state.copy(name = action.value)
            is LifecycleDemoAction.SetEmail -> state.copy(email = action.value)
            is LifecycleDemoAction.SetNotes -> state.copy(notes = action.value)
            is LifecycleDemoAction.ClearFields -> state.copy(
                name = "",
                email = "",
                notes = "",
                timesCleared = state.timesCleared + 1
            )
        }
    }
    override val createLogic: (storeAccessor: StoreAccessor) -> ModuleLogic =
        { storeAccessor -> LifecycleDemoLogic(storeAccessor) }
}

class LifecycleDemoLogic(
    private val storeAccessor: StoreAccessor
) : ModuleLogic()
