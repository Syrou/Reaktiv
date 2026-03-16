import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import io.github.syrou.reaktiv.core.Module
import io.github.syrou.reaktiv.core.ModuleAction
import io.github.syrou.reaktiv.core.ModuleLogic
import io.github.syrou.reaktiv.core.ModuleState
import io.github.syrou.reaktiv.core.StoreAccessor
import io.github.syrou.reaktiv.core.createStore
import io.github.syrou.reaktiv.core.util.selectState
import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.createNavigationModule
import io.github.syrou.reaktiv.navigation.definition.Modal
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.extension.navigation
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.DurationUnit
import kotlin.time.toDuration

/**
 * Verifies that every navigation DSL operation can appear before or after a
 * navigateTo(dynamic-graph) call in any order, without being silently dropped.
 *
 * Dynamic graphs (those whose start is an async lambda) previously caused a
 * new NavigationBuilder to be constructed containing only the resolved Navigate
 * step, discarding all other operations in the original builder.
 *
 * Covered operations:
 *   Preceding: clearBackStack, navigateBack, popUpTo, resumePendingNavigation
 *   Following: navigateTo(screen), navigateBack, popUpTo, resumePendingNavigation
 *   Flags:     dismissModals (shouldDismissModals carried to resolved Navigate)
 *   Params:    forwarded to resolved screen regardless of surrounding operations
 *   Combos:    preceding + following in same block
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DynamicGraphOperationOrderTest {

    private fun screen(route: String) = object : Screen {
        override val route = route
        override val enterTransition = NavTransition.None
        override val exitTransition = NavTransition.None

        @Composable
        override fun Content(params: Params) { Text(route) }
    }

    private fun modal(route: String) = object : Modal {
        override val route = route
        override val enterTransition = NavTransition.None
        override val exitTransition = NavTransition.None

        @Composable
        override fun Content(params: Params) { Text(route) }
    }

    private val splash     = screen("splash")
    private val login      = screen("login")
    private val settings   = screen("settings")
    private val dashboard  = screen("dashboard")
    private val details    = screen("details")
    private val alertModal = modal("alert")

    @Serializable
    data class AuthState(val isAuthenticated: Boolean = false) : ModuleState

    sealed class AuthAction(tag: KClass<*>) : ModuleAction(tag) {
        data object Login : AuthAction(AuthModule::class)
    }

    object AuthModule : Module<AuthState, AuthAction> {
        override val initialState = AuthState()
        override val reducer: (AuthState, AuthAction) -> AuthState = { state, action ->
            when (action) {
                AuthAction.Login -> state.copy(isAuthenticated = true)
            }
        }
        override val createLogic: (StoreAccessor) -> ModuleLogic = { object : ModuleLogic() {} }
    }

    /**
     * Open workspace (no guard) — for operation-order tests that do not
     * require pending-navigation setup.
     */
    private fun createOpenModule() = createNavigationModule {
        rootGraph {
            start(splash)
            screens(splash, login, settings)
            modals(alertModal)
            graph("workspace") {
                start(route = { _ -> dashboard })
                screens(dashboard, details)
            }
        }
    }

    /**
     * Protected workspace (PendAndRedirect guard) — for tests that verify
     * resumePendingNavigation in combination with dynamic-graph navigation.
     */
    private fun createProtectedModule() = createNavigationModule {
        rootGraph {
            start(splash)
            screens(splash, login)
            intercept(guard = { store ->
                if (store.selectState<AuthState>().value.isAuthenticated) GuardResult.Allow
                else GuardResult.PendAndRedirectTo(navigatable = login)
            }) {
                graph("workspace") {
                    start(route = { _ -> dashboard })
                    screens(dashboard, details)
                }
            }
        }
    }

    @Test
    fun `clearBackStack before navigateTo dynamic graph — backStack cleared before resolve`() =
        runTest(timeout = 10.toDuration(DurationUnit.SECONDS)) {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val store = createStore {
                module(createOpenModule())
                coroutineContext(dispatcher)
            }
            advanceUntilIdle()

            store.navigation { navigateTo("login") }
            advanceUntilIdle()
            assertEquals(2, store.selectState<NavigationState>().first().backStack.size)

            store.navigation {
                clearBackStack()
                navigateTo("workspace")
            }
            advanceUntilIdle()

            val state = store.selectState<NavigationState>().first()
            assertEquals("dashboard", state.currentEntry.route)
            assertEquals(1, state.backStack.size, "clearBackStack must run before dynamic resolve")
        }

    @Test
    fun `navigateBack before navigateTo dynamic graph — back executes before resolve`() =
        runTest(timeout = 10.toDuration(DurationUnit.SECONDS)) {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val store = createStore {
                module(createOpenModule())
                coroutineContext(dispatcher)
            }
            advanceUntilIdle()

            store.navigation { navigateTo("login") }
            advanceUntilIdle()
            assertEquals("login", store.selectState<NavigationState>().first().currentEntry.route)

            store.navigation {
                navigateBack()
                navigateTo("workspace")
            }
            advanceUntilIdle()

            val state = store.selectState<NavigationState>().first()
            assertEquals("dashboard", state.currentEntry.route)
            assertEquals(2, state.backStack.size, "back removed login; workspace resolve added dashboard")
            assertEquals("splash", state.backStack.first().route)
        }

    @Test
    fun `popUpTo before navigateTo dynamic graph — stack trimmed before resolve`() =
        runTest(timeout = 10.toDuration(DurationUnit.SECONDS)) {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val store = createStore {
                module(createOpenModule())
                coroutineContext(dispatcher)
            }
            advanceUntilIdle()

            store.navigation { navigateTo("login") }
            advanceUntilIdle()
            store.navigation { navigateTo("settings") }
            advanceUntilIdle()
            assertEquals(3, store.selectState<NavigationState>().first().backStack.size)

            store.navigation {
                popUpTo("splash", inclusive = false)
                navigateTo("workspace")
            }
            advanceUntilIdle()

            val state = store.selectState<NavigationState>().first()
            assertEquals("dashboard", state.currentEntry.route)
            assertEquals(2, state.backStack.size, "splash + dashboard; login and settings popped")
            assertEquals("splash", state.backStack.first().route)
        }

    @Test
    fun `resumePendingNavigation before navigateTo dynamic graph — pending consumed first`() =
        runTest(timeout = 10.toDuration(DurationUnit.SECONDS)) {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val store = createStore {
                module(AuthModule)
                module(createProtectedModule())
                coroutineContext(dispatcher)
            }
            advanceUntilIdle()

            store.navigation { navigateTo("workspace/details") }
            advanceUntilIdle()
            assertNotNull(store.selectState<NavigationState>().first().pendingNavigation)

            store.dispatch(AuthAction.Login)
            advanceUntilIdle()

            store.navigation {
                clearBackStack()
                resumePendingNavigation()
            }
            advanceUntilIdle()

            val state = store.selectState<NavigationState>().first()
            assertEquals("details", state.currentEntry.route)
            assertNull(state.pendingNavigation, "pendingNavigation must be consumed")
        }

    @Test
    fun `navigateTo screen after navigateTo dynamic graph — both navigations execute`() =
        runTest(timeout = 10.toDuration(DurationUnit.SECONDS)) {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val store = createStore {
                module(createOpenModule())
                coroutineContext(dispatcher)
            }
            advanceUntilIdle()

            store.navigation {
                navigateTo("workspace")
                navigateTo("settings")
            }
            advanceUntilIdle()

            val state = store.selectState<NavigationState>().first()
            assertEquals("settings", state.currentEntry.route)
            assertTrue(state.backStack.any { it.route == "dashboard" }, "dashboard must be in backStack")
        }

    @Test
    fun `navigateBack after navigateTo dynamic graph — back executes after resolve`() =
        runTest(timeout = 10.toDuration(DurationUnit.SECONDS)) {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val store = createStore {
                module(createOpenModule())
                coroutineContext(dispatcher)
            }
            advanceUntilIdle()

            store.navigation { navigateTo("login") }
            advanceUntilIdle()

            store.navigation {
                navigateTo("workspace")
                navigateBack()
            }
            advanceUntilIdle()

            val state = store.selectState<NavigationState>().first()
            assertEquals("login", state.currentEntry.route, "back after workspace resolve must return to login")
        }

    @Test
    fun `popUpTo after navigateTo dynamic graph — stack trimmed after resolve`() =
        runTest(timeout = 10.toDuration(DurationUnit.SECONDS)) {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val store = createStore {
                module(createOpenModule())
                coroutineContext(dispatcher)
            }
            advanceUntilIdle()

            store.navigation { navigateTo("login") }
            advanceUntilIdle()

            store.navigation {
                navigateTo("workspace")
                popUpTo("splash", inclusive = false)
            }
            advanceUntilIdle()

            val state = store.selectState<NavigationState>().first()
            assertEquals("dashboard", state.currentEntry.route)
            assertEquals(2, state.backStack.size, "navigateTo workspace then popUpTo splash: dashboard re-added on top of splash")
            assertEquals("splash", state.backStack.first().route)
        }

    @Test
    fun `resumePendingNavigation after navigateTo dynamic graph — pending resolved after`() =
        runTest(timeout = 10.toDuration(DurationUnit.SECONDS)) {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val store = createStore {
                module(AuthModule)
                module(createProtectedModule())
                coroutineContext(dispatcher)
            }
            advanceUntilIdle()

            store.navigation { navigateTo("workspace/details") }
            advanceUntilIdle()
            assertEquals("login", store.selectState<NavigationState>().first().currentEntry.route)
            assertNotNull(store.selectState<NavigationState>().first().pendingNavigation)

            store.dispatch(AuthAction.Login)
            advanceUntilIdle()

            store.navigation {
                navigateTo("workspace")
                resumePendingNavigation()
            }
            advanceUntilIdle()

            val state = store.selectState<NavigationState>().first()
            assertEquals("details", state.currentEntry.route)
            assertNull(state.pendingNavigation)
        }

    @Test
    fun `clearBackStack before and navigateTo screen after dynamic graph — all operations execute`() =
        runTest(timeout = 10.toDuration(DurationUnit.SECONDS)) {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val store = createStore {
                module(createOpenModule())
                coroutineContext(dispatcher)
            }
            advanceUntilIdle()

            store.navigation { navigateTo("login") }
            advanceUntilIdle()
            assertEquals(2, store.selectState<NavigationState>().first().backStack.size)

            store.navigation {
                clearBackStack()
                navigateTo("workspace")
                navigateTo("details")
            }
            advanceUntilIdle()

            val state = store.selectState<NavigationState>().first()
            assertEquals("details", state.currentEntry.route)
            assertFalse(state.backStack.any { it.route == "login" }, "clearBackStack must have removed login")
            assertTrue(state.backStack.any { it.route == "dashboard" }, "dashboard must be intermediate entry")
        }

    @Test
    fun `clearBackStack before and resumePendingNavigation after dynamic graph — all operations execute`() =
        runTest(timeout = 10.toDuration(DurationUnit.SECONDS)) {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val store = createStore {
                module(AuthModule)
                module(createProtectedModule())
                coroutineContext(dispatcher)
            }
            advanceUntilIdle()

            store.navigation { navigateTo("workspace/details") }
            advanceUntilIdle()
            assertEquals("login", store.selectState<NavigationState>().first().currentEntry.route)
            assertNotNull(store.selectState<NavigationState>().first().pendingNavigation)

            store.dispatch(AuthAction.Login)
            advanceUntilIdle()

            store.navigation {
                clearBackStack()
                navigateTo("workspace")
                resumePendingNavigation()
            }
            advanceUntilIdle()

            val state = store.selectState<NavigationState>().first()
            assertEquals("details", state.currentEntry.route)
            assertNull(state.pendingNavigation)
            assertFalse(state.backStack.any { it.route == "login" }, "clearBackStack must have removed login")
        }

    @Test
    fun `popUpTo before and navigateTo screen after dynamic graph — all operations execute`() =
        runTest(timeout = 10.toDuration(DurationUnit.SECONDS)) {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val store = createStore {
                module(createOpenModule())
                coroutineContext(dispatcher)
            }
            advanceUntilIdle()

            store.navigation { navigateTo("login") }
            advanceUntilIdle()
            store.navigation { navigateTo("settings") }
            advanceUntilIdle()
            assertEquals(3, store.selectState<NavigationState>().first().backStack.size)

            store.navigation {
                popUpTo("splash", inclusive = false)
                navigateTo("workspace")
                navigateTo("details")
            }
            advanceUntilIdle()

            val state = store.selectState<NavigationState>().first()
            assertEquals("details", state.currentEntry.route)
            assertFalse(state.backStack.any { it.route == "settings" }, "settings must have been popped")
            assertFalse(state.backStack.any { it.route == "login" }, "login must have been popped")
            assertTrue(state.backStack.any { it.route == "splash" })
            assertTrue(state.backStack.any { it.route == "dashboard" })
        }

    @Test
    fun `popUpTo before and resumePendingNavigation after dynamic graph — all operations execute`() =
        runTest(timeout = 10.toDuration(DurationUnit.SECONDS)) {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val store = createStore {
                module(AuthModule)
                module(createProtectedModule())
                coroutineContext(dispatcher)
            }
            advanceUntilIdle()

            store.navigation { navigateTo("workspace/details") }
            advanceUntilIdle()
            assertNotNull(store.selectState<NavigationState>().first().pendingNavigation)

            store.dispatch(AuthAction.Login)
            advanceUntilIdle()

            store.navigation { navigateTo("splash") }
            advanceUntilIdle()
            assertEquals(2, store.selectState<NavigationState>().first().backStack.size)

            store.navigation {
                popUpTo("login", inclusive = false)
                navigateTo("workspace")
                resumePendingNavigation()
            }
            advanceUntilIdle()

            val state = store.selectState<NavigationState>().first()
            assertEquals("details", state.currentEntry.route)
            assertNull(state.pendingNavigation)
            assertTrue(state.backStack.any { it.route == "login" })
            assertFalse(state.backStack.any { it.route == "splash" }, "splash must not be synthesized when backStack already has entries")
        }

    @Test
    fun `clearBackStack before does not lose params forwarded to dynamic graph`() =
        runTest(timeout = 10.toDuration(DurationUnit.SECONDS)) {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val store = createStore {
                module(createOpenModule())
                coroutineContext(dispatcher)
            }
            advanceUntilIdle()

            store.navigation { navigateTo("login") }
            advanceUntilIdle()

            store.navigation {
                clearBackStack()
                navigateTo("workspace") {
                    put("ref", "signup")
                    put("step", 3)
                }
            }
            advanceUntilIdle()

            val state = store.selectState<NavigationState>().first()
            assertEquals("dashboard", state.currentEntry.route)
            assertEquals(1, state.backStack.size)
            assertEquals("signup", state.currentEntry.params.getTyped<String>("ref"))
            assertEquals(3, state.currentEntry.params.getTyped<Int>("step"))
        }

    @Test
    fun `params forwarded to resolved screen when resumePendingNavigation also follows`() =
        runTest(timeout = 10.toDuration(DurationUnit.SECONDS)) {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val store = createStore {
                module(createOpenModule())
                coroutineContext(dispatcher)
            }
            advanceUntilIdle()

            store.navigation {
                navigateTo("workspace") {
                    put("ref", "direct")
                }
                resumePendingNavigation()
            }
            advanceUntilIdle()

            val state = store.selectState<NavigationState>().first()
            assertEquals("dashboard", state.currentEntry.route)
            assertEquals("direct", state.currentEntry.params.getTyped<String>("ref"))
        }

    @Test
    fun `params forwarded when both clearBackStack and navigateTo screen surround dynamic graph`() =
        runTest(timeout = 10.toDuration(DurationUnit.SECONDS)) {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val store = createStore {
                module(createOpenModule())
                coroutineContext(dispatcher)
            }
            advanceUntilIdle()

            store.navigation { navigateTo("login") }
            advanceUntilIdle()

            store.navigation {
                clearBackStack()
                navigateTo("workspace") { put("token", "abc") }
                navigateTo("details")
            }
            advanceUntilIdle()

            val state = store.selectState<NavigationState>().first()
            assertEquals("details", state.currentEntry.route)
            assertFalse(state.backStack.any { it.route == "login" })
            val dashboardEntry = state.backStack.first { it.route == "dashboard" }
            assertEquals("abc", dashboardEntry.params.getTyped<String>("token"))
        }

    @Test
    fun `dismissModals flag carried to resolved navigate when dynamic graph is target`() =
        runTest(timeout = 10.toDuration(DurationUnit.SECONDS)) {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val store = createStore {
                module(createOpenModule())
                coroutineContext(dispatcher)
            }
            advanceUntilIdle()

            store.navigation { navigateTo("alert") }
            advanceUntilIdle()
            val withModal = store.selectState<NavigationState>().first()
            assertTrue(withModal.isCurrentModal, "alert modal should be open")

            store.navigation {
                navigateTo("workspace")
                dismissModals()
            }
            advanceUntilIdle()

            val state = store.selectState<NavigationState>().first()
            assertEquals("dashboard", state.currentEntry.route)
            assertFalse(state.isCurrentModal, "modal must be dismissed by dismissModals flag")
            assertFalse(state.backStack.any { it.route == "alert" }, "alert must be gone from backStack")
        }
}
