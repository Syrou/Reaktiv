package io.github.syrou.reaktiv.test.navigation

import androidx.compose.runtime.Composable
import io.github.syrou.reaktiv.core.Module
import io.github.syrou.reaktiv.core.ModuleAction
import io.github.syrou.reaktiv.core.ModuleLogic
import io.github.syrou.reaktiv.core.ModuleState
import io.github.syrou.reaktiv.core.StoreAccessor
import io.github.syrou.reaktiv.core.util.selectState
import io.github.syrou.reaktiv.navigation.createNavigationModule
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.extension.navigation
import io.github.syrou.reaktiv.navigation.model.GuardResult
import io.github.syrou.reaktiv.navigation.param.Params
import io.github.syrou.reaktiv.navigation.transition.NavTransition
import io.github.syrou.reaktiv.test.reaktivTest
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@Serializable
data class NavTestAuthState(val isAuthenticated: Boolean = false) : ModuleState

sealed class NavTestAuthAction : ModuleAction(NavTestAuthModule::class) {
    data object Login : NavTestAuthAction()
}

object NavTestAuthModule : Module<NavTestAuthState, NavTestAuthAction> {
    override val initialState = NavTestAuthState()
    override val reducer: (NavTestAuthState, NavTestAuthAction) -> NavTestAuthState = { state, action ->
        when (action) {
            NavTestAuthAction.Login -> state.copy(isAuthenticated = true)
        }
    }
    override val createLogic: (StoreAccessor) -> ModuleLogic = { object : ModuleLogic() {} }
}

class NavigationAssertionsTest {

    private fun screen(route: String) = object : Screen {
        override val route = route
        override val enterTransition = NavTransition.None
        override val exitTransition = NavTransition.None

        @Composable
        override fun Content(params: Params) {}
    }

    private val startScreen = screen("start")
    private val homeScreen = screen("home")
    private val detailScreen = screen("detail")

    private fun navModule() = createNavigationModule {
        rootGraph {
            start(startScreen)
            screens(startScreen, homeScreen, detailScreen)
        }
    }

    @Test
    fun `assertCurrentRoute passes for the active route`() = reaktivTest(NavTestAuthModule, navModule()) {
        store.navigation { navigateTo("home") }
        assertCurrentRoute("home")
        assertCurrentPath("home")
    }

    @Test
    fun `assertCurrentRoute fails with an informative message`() = reaktivTest(NavTestAuthModule, navModule()) {
        store.navigation { navigateTo("home") }
        val failure = assertFailsWith<AssertionError> {
            assertCurrentRoute("detail")
        }
        assertTrue(failure.message!!.contains("detail"))
        assertTrue(failure.message!!.contains("home"))
    }

    @Test
    fun `assertBackStack compares the full stack in order`() = reaktivTest(NavTestAuthModule, navModule()) {
        store.navigation { navigateTo("home") }
        store.navigation { navigateTo("detail") }
        assertBackStack("start", "home", "detail")

        val failure = assertFailsWith<AssertionError> {
            assertBackStack("start", "detail")
        }
        assertTrue(failure.message!!.contains("[start, detail]"))
    }

    @Test
    fun `awaitRoute suspends until navigation lands`() = reaktivTest(NavTestAuthModule, navModule()) {
        store.navigation { navigateTo("home") }
        val state = awaitRoute("home")
        assertEquals("home", state.currentEntry.route)
    }

    @Test
    fun `evaluateGuard runs a guard against the test store`() = reaktivTest(NavTestAuthModule, navModule()) {
        val guard: suspend (StoreAccessor) -> GuardResult = { accessor ->
            if (accessor.selectState<NavTestAuthState>().first().isAuthenticated) GuardResult.Allow
            else GuardResult.Reject
        }

        assertEquals(GuardResult.Reject, evaluateGuard(guard))
        dispatch(NavTestAuthAction.Login)
        assertEquals(GuardResult.Allow, evaluateGuard(guard))
    }
}
