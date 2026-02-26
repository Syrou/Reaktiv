package eu.syrou.androidexample.reaktiv.auth

import eu.syrou.androidexample.reaktiv.auth.AuthModule.AuthAction
import eu.syrou.androidexample.reaktiv.auth.AuthModule.AuthState
import io.github.syrou.reaktiv.core.ModuleLogic
import io.github.syrou.reaktiv.core.StoreAccessor
import io.github.syrou.reaktiv.core.util.selectState
import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.extension.navigation
import io.github.syrou.reaktiv.navigation.extension.resumePendingNavigation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlin.random.Random

class AuthLogic(private val storeAccessor: StoreAccessor) : ModuleLogic<AuthAction>() {

    /**
     * Determines the initial session state on cold start. In a real app this reads a
     * stored token from encrypted storage and validates it with the server — that is
     * where the startup delay would live.
     */
    suspend fun initializeSession() {
        delay(5000)
        storeAccessor.dispatch(AuthAction.SetAuthenticated(Random.nextBoolean()))
        //storeAccessor.dispatch(AuthAction.SetAuthenticated(false))
    }

    /**
     * Returns the current session state. Suspends until isAuthenticated is non-null,
     * which is guaranteed once bootstrap completes.
     */
    suspend fun checkSession(): Boolean {
        return storeAccessor.selectState<AuthState>()
            .mapNotNull { it.isAuthenticated }
            .first()
    }

    /**
     * Simulates a login network call. Sets loading, waits, then commits authenticated=true
     * and navigates to the pending deep-link destination (or home if none).
     */
    suspend fun login() {
        storeAccessor.dispatch(AuthAction.SetLoading(true))
        delay(1500)
        storeAccessor.dispatchAndAwait(AuthAction.SetAuthenticated(true))
        val hasPending = storeAccessor.selectState<NavigationState>().first().pendingNavigation != null
        if (hasPending) {
            println("HERPADERPA - hasPending")
            storeAccessor.resumePendingNavigation()
        } else {
            println("HERPADERPA - does not have pending")
            storeAccessor.navigation {
                clearBackStack()
                navigateTo("home")
            }
        }
    }
}
