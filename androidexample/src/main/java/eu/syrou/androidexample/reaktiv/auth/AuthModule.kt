package eu.syrou.androidexample.reaktiv.auth

import io.github.syrou.reaktiv.core.Module
import io.github.syrou.reaktiv.core.ModuleAction
import io.github.syrou.reaktiv.core.ModuleLogic
import io.github.syrou.reaktiv.core.ModuleState
import io.github.syrou.reaktiv.core.StoreAccessor
import kotlinx.serialization.Serializable

object AuthModule : Module<AuthModule.AuthState, AuthModule.AuthAction> {

    @Serializable
    data class AuthState(
        val isAuthenticated: Boolean? = null,
        val isLoading: Boolean = false
    ) : ModuleState

    @Serializable
    sealed class AuthAction : ModuleAction(AuthModule::class) {
        @Serializable
        data class SetAuthenticated(val value: Boolean?) : AuthAction()
        @Serializable
        data class SetLoading(val value: Boolean) : AuthAction()
    }

    override val initialState = AuthState()

    override val reducer: (AuthState, AuthAction) -> AuthState = { state, action ->
        when (action) {
            is AuthAction.SetAuthenticated -> state.copy(isAuthenticated = action.value)
            is AuthAction.SetLoading -> state.copy(isLoading = action.value)
        }
    }

    override val createLogic: (StoreAccessor) -> ModuleLogic<AuthAction> = { storeAccessor ->
        AuthLogic(storeAccessor)
    }
}
