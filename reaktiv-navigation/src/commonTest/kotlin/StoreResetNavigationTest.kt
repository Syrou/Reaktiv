import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import io.github.syrou.reaktiv.core.Module
import io.github.syrou.reaktiv.core.ModuleAction
import io.github.syrou.reaktiv.core.ModuleLogic
import io.github.syrou.reaktiv.core.ModuleState
import io.github.syrou.reaktiv.core.StoreAccessor
import io.github.syrou.reaktiv.core.createStore
import io.github.syrou.reaktiv.core.util.selectState
import io.github.syrou.reaktiv.navigation.NavigationAction
import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.createNavigationModule
import io.github.syrou.reaktiv.navigation.definition.Modal
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.extension.navigation
import io.github.syrou.reaktiv.navigation.extension.resumePendingNavigation
import io.github.syrou.reaktiv.navigation.model.GuardResult
import io.github.syrou.reaktiv.navigation.param.Params
import io.github.syrou.reaktiv.navigation.transition.NavTransition
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.DurationUnit
import kotlin.time.toDuration

/**
 * Verifies that store.reset() correctly restores navigation state to its initial
 * condition and that navigation continues to function normally afterwards.
 *
 * Also verifies that multi-module reset (auth + navigation together) resets
 * each module independently and the guard system re-evaluates against the
 * freshly-reset auth state.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StoreResetNavigationTest {

    // --- Screen / modal helpers ---

    private fun screen(route: String) = object : Screen {
        override val route = route
        override val enterTransition = NavTransition.None
        override val exitTransition = NavTransition.None
        override val requiresAuth = false

        @Composable
        override fun Content(params: Params) { Text(route) }
    }

    private fun modal(route: String) = object : Modal {
        override val route = route
        override val enterTransition = NavTransition.None
        override val exitTransition = NavTransition.None
        override val requiresAuth = false

        @Composable
        override fun Content(params: Params) { Text(route) }
    }

    private val homeScreen     = screen("home")
    private val profileScreen  = screen("profile")
    private val settingsScreen = screen("settings")
    private val loginScreen    = screen("login")
    private val workspaceScreen = screen("workspace")
    private val notificationModal = modal("notification")

    // --- Auth module ---

    @Serializable
    data class AuthState(val isAuthenticated: Boolean = false) : ModuleState

    sealed class AuthAction(tag: KClass<*>) : ModuleAction(tag) {
        data object Login  : AuthAction(AuthModule::class)
        data object Logout : AuthAction(AuthModule::class)
    }

    object AuthModule : Module<AuthState, AuthAction> {
        override val initialState = AuthState()
        override val reducer: (AuthState, AuthAction) -> AuthState = { state, action ->
            when (action) {
                AuthAction.Login  -> state.copy(isAuthenticated = true)
                AuthAction.Logout -> state.copy(isAuthenticated = false)
            }
        }
        override val createLogic: (StoreAccessor) -> ModuleLogic =
            { object : ModuleLogic() {} }
    }

    // --- Navigation module factories ---

    private fun simpleNavModule() = createNavigationModule {
        rootGraph {
            startScreen(homeScreen)
            screens(homeScreen, profileScreen, settingsScreen)
            modals(notificationModal)
        }
    }

    private fun guardedNavModule() = createNavigationModule {
        rootGraph {
            startScreen(homeScreen)
            screens(homeScreen, loginScreen)
            intercept(
                guard = { store ->
                    if (store.selectState<AuthState>().value.isAuthenticated) GuardResult.Allow
                    else GuardResult.PendAndRedirectTo(
                        navigatable = loginScreen,
                        metadata = mapOf("source" to "protected")
                    )
                }
            ) {
                graph("protected") {
                    entry(workspaceScreen)
                    screens(workspaceScreen, settingsScreen)
                }
            }
        }
    }

    // --- Tests ---

    @Test
    fun `reset returns navigation to initial screen`() =
        runTest(timeout = 10.toDuration(DurationUnit.SECONDS)) {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val store = createStore {
                module(simpleNavModule())
                coroutineContext(dispatcher)
            }
            advanceUntilIdle()

            store.navigation { navigateTo("profile") }
            advanceUntilIdle()
            store.navigation { navigateTo("settings") }
            advanceUntilIdle()
            assertEquals("settings", store.selectState<NavigationState>().first().currentEntry.route)

            store.reset()
            advanceUntilIdle()

            val state = store.selectState<NavigationState>().first()
            assertEquals("home", state.currentEntry.route)
            assertEquals(1, state.backStack.size)
        }

    @Test
    fun `reset clears pending navigation`() =
        runTest(timeout = 10.toDuration(DurationUnit.SECONDS)) {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val store = createStore {
                module(AuthModule)
                module(guardedNavModule())
                coroutineContext(dispatcher)
            }
            advanceUntilIdle()

            store.navigation { navigateTo("protected/workspace") }
            advanceUntilIdle()
            assertEquals("login", store.selectState<NavigationState>().first().currentEntry.route)
            assertTrue(store.selectState<NavigationState>().first().pendingNavigation != null)

            store.reset()
            advanceUntilIdle()

            val state = store.selectState<NavigationState>().first()
            assertEquals("home", state.currentEntry.route)
            assertNull(state.pendingNavigation)
        }

    @Test
    fun `reset clears open modal contexts`() =
        runTest(timeout = 10.toDuration(DurationUnit.SECONDS)) {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val store = createStore {
                module(simpleNavModule())
                coroutineContext(dispatcher)
            }
            advanceUntilIdle()

            store.navigation { navigateTo("notification") }
            advanceUntilIdle()
            assertTrue(store.selectState<NavigationState>().first().isCurrentModal)

            store.reset()
            advanceUntilIdle()

            val state = store.selectState<NavigationState>().first()
            assertEquals("home", state.currentEntry.route)
            assertFalse(state.isCurrentModal)
            assertTrue(state.activeModalContexts.isEmpty())
        }

    @Test
    fun `navigation works normally after reset`() =
        runTest(timeout = 10.toDuration(DurationUnit.SECONDS)) {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val store = createStore {
                module(simpleNavModule())
                coroutineContext(dispatcher)
            }
            advanceUntilIdle()

            store.navigation { navigateTo("profile") }
            advanceUntilIdle()
            store.reset()
            advanceUntilIdle()

            assertEquals("home", store.selectState<NavigationState>().first().currentEntry.route)

            store.navigation { navigateTo("settings") }
            advanceUntilIdle()
            assertEquals("settings", store.selectState<NavigationState>().first().currentEntry.route)

            store.navigation { navigateTo("profile") }
            advanceUntilIdle()
            assertEquals("profile", store.selectState<NavigationState>().first().currentEntry.route)
        }

    @Test
    fun `reset also resets auth module so guard re-evaluates on next navigation`() =
        runTest(timeout = 10.toDuration(DurationUnit.SECONDS)) {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val store = createStore {
                module(AuthModule)
                module(guardedNavModule())
                coroutineContext(dispatcher)
            }
            store.dispatch(AuthAction.Login)
            advanceUntilIdle()

            store.navigation { navigateTo("protected/workspace") }
            advanceUntilIdle()
            assertEquals("workspace", store.selectState<NavigationState>().first().currentEntry.route)

            store.reset()
            advanceUntilIdle()

            // Auth is now false (reset to initialState), navigation is back to home
            assertFalse(store.selectState<AuthState>().value.isAuthenticated)
            assertEquals("home", store.selectState<NavigationState>().first().currentEntry.route)

            // Guard should now deny access — navigation should pend and redirect
            store.navigation { navigateTo("protected/workspace") }
            advanceUntilIdle()

            val afterNav = store.selectState<NavigationState>().first()
            assertEquals("login", afterNav.currentEntry.route)
            assertTrue(afterNav.pendingNavigation != null)
            assertEquals("protected/workspace", afterNav.pendingNavigation!!.route)
        }

    @Test
    fun `reset then re-authenticate and resume pending works correctly`() =
        runTest(timeout = 10.toDuration(DurationUnit.SECONDS)) {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val store = createStore {
                module(AuthModule)
                module(guardedNavModule())
                coroutineContext(dispatcher)
            }
            store.dispatch(AuthAction.Login)
            advanceUntilIdle()
            store.navigation { navigateTo("protected/workspace") }
            advanceUntilIdle()
            assertEquals("workspace", store.selectState<NavigationState>().first().currentEntry.route)

            store.reset()
            advanceUntilIdle()

            // After reset: unauthenticated, on home
            store.navigation { navigateTo("protected/workspace") }
            advanceUntilIdle()
            assertEquals("login", store.selectState<NavigationState>().first().currentEntry.route)

            // Re-authenticate and resume
            store.dispatch(AuthAction.Login)
            advanceUntilIdle()
            store.resumePendingNavigation()
            advanceUntilIdle()

            val state = store.selectState<NavigationState>().first()
            assertEquals("workspace", state.currentEntry.route)
            assertNull(state.pendingNavigation)
        }

    @Test
    fun `sequential resets each return to initial state`() =
        runTest(timeout = 10.toDuration(DurationUnit.SECONDS)) {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val store = createStore {
                module(simpleNavModule())
                coroutineContext(dispatcher)
            }
            advanceUntilIdle()

            store.navigation { navigateTo("profile") }
            advanceUntilIdle()
            val firstReset = store.reset()
            advanceUntilIdle()

            assertTrue(firstReset)
            assertEquals("home", store.selectState<NavigationState>().first().currentEntry.route)

            store.navigation { navigateTo("settings") }
            advanceUntilIdle()
            val secondReset = store.reset()
            advanceUntilIdle()

            assertTrue(secondReset)
            assertEquals("home", store.selectState<NavigationState>().first().currentEntry.route)
            assertEquals(1, store.selectState<NavigationState>().first().backStack.size)
        }

    @Test
    fun `dispatch ClearPendingNavigation removes pending without full reset`() =
        runTest(timeout = 10.toDuration(DurationUnit.SECONDS)) {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val store = createStore {
                module(AuthModule)
                module(guardedNavModule())
                coroutineContext(dispatcher)
            }
            advanceUntilIdle()

            store.navigation { navigateTo("protected/workspace") }
            advanceUntilIdle()
            assertTrue(store.selectState<NavigationState>().first().pendingNavigation != null)

            store.dispatch(NavigationAction.ClearPendingNavigation)
            advanceUntilIdle()

            val state = store.selectState<NavigationState>().first()
            assertNull(state.pendingNavigation)
            assertEquals("login", state.currentEntry.route, "Route must not change after clearing pending")
        }
}
