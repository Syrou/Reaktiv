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
import io.github.syrou.reaktiv.navigation.extension.navigateBack
import io.github.syrou.reaktiv.navigation.extension.navigateDeepLink
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.DurationUnit
import kotlin.time.toDuration

/**
 * End-to-end integration tests covering realistic multi-step user journeys.
 *
 * App structure:
 * - Root graph: splash, login, check-email, register screens + notification modal
 * - Intercepted workspace graph (auth required): home, tasks/{taskId}, settings
 *
 * Guard strategy: PendAndRedirectTo(login) when unauthenticated, or Reject variant per test.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UserJourneyIntegrationTest {

    // --- Screen and modal definitions ---

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

    private val splashScreen        = screen("splash")
    private val loginScreen         = screen("login")
    private val checkEmailScreen    = screen("check-email")
    private val registerScreen      = screen("register")
    private val workspaceHomeScreen = screen("home")
    private val taskDetailScreen    = screen("tasks/{taskId}")
    private val settingsScreen      = screen("settings")
    private val notificationModal   = modal("notification")

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

    private fun pendAndRedirectModule(displayHint: String = "Please log in to continue") =
        createNavigationModule {
            rootGraph {
                startScreen(splashScreen)
                screens(splashScreen, loginScreen, checkEmailScreen, registerScreen)
                modals(notificationModal)
                intercept(
                    guard = { store ->
                        if (store.selectState<AuthState>().value.isAuthenticated) GuardResult.Allow
                        else GuardResult.PendAndRedirectTo(
                            navigatable = loginScreen,
                            metadata = mapOf("source" to "workspace"),
                            displayHint = displayHint
                        )
                    }
                ) {
                    graph("workspace") {
                        entry(workspaceHomeScreen)
                        screens(workspaceHomeScreen, taskDetailScreen, settingsScreen)
                    }
                }
            }
        }

    private fun rejectGuardModule() = createNavigationModule {
        rootGraph {
            startScreen(splashScreen)
            screens(splashScreen, loginScreen)
            intercept(guard = { store ->
                if (store.selectState<AuthState>().value.isAuthenticated) GuardResult.Allow
                else GuardResult.Reject
            }) {
                graph("workspace") {
                    entry(workspaceHomeScreen)
                    screens(workspaceHomeScreen, settingsScreen)
                }
            }
        }
    }

    // --- Tests ---

    @Test
    fun `unauthenticated access pends navigation and redirects to login`() =
        runTest(timeout = 10.toDuration(DurationUnit.SECONDS)) {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val store = createStore {
                module(AuthModule)
                module(pendAndRedirectModule())
                coroutineContext(dispatcher)
            }
            advanceUntilIdle()

            store.navigation { navigateTo("workspace/home") }
            advanceUntilIdle()

            val state = store.selectState<NavigationState>().first()
            assertEquals("login", state.currentEntry.route)
            assertNotNull(state.pendingNavigation)
            assertEquals("workspace/home", state.pendingNavigation!!.route)
            assertEquals("workspace", state.pendingNavigation!!.metadata["source"])
            assertEquals("Please log in to continue", state.pendingNavigation!!.displayHint)
        }

    @Test
    fun `pending navigation survives multi-step auth flow then resume lands on protected screen`() =
        runTest(timeout = 10.toDuration(DurationUnit.SECONDS)) {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val store = createStore {
                module(AuthModule)
                module(pendAndRedirectModule(displayHint = "Join your team"))
                coroutineContext(dispatcher)
            }
            advanceUntilIdle()

            store.navigation { navigateTo("workspace/home") }
            advanceUntilIdle()
            assertEquals("login", store.selectState<NavigationState>().first().currentEntry.route)
            assertNotNull(store.selectState<NavigationState>().first().pendingNavigation)

            store.navigation { navigateTo("check-email") }
            advanceUntilIdle()
            val atCheckEmail = store.selectState<NavigationState>().first()
            assertEquals("check-email", atCheckEmail.currentEntry.route)
            assertNotNull(atCheckEmail.pendingNavigation, "Pending must survive navigation to check-email")
            assertEquals("Join your team", atCheckEmail.pendingNavigation!!.displayHint)

            store.navigation { navigateTo("register") }
            advanceUntilIdle()
            val atRegister = store.selectState<NavigationState>().first()
            assertEquals("register", atRegister.currentEntry.route)
            assertNotNull(atRegister.pendingNavigation, "Pending must survive navigation to register")

            store.dispatch(AuthAction.Login)
            advanceUntilIdle()
            store.resumePendingNavigation()
            advanceUntilIdle()

            val afterResume = store.selectState<NavigationState>().first()
            assertEquals("home", afterResume.currentEntry.route)
            assertNull(afterResume.pendingNavigation)
            assertTrue(store.selectState<AuthState>().value.isAuthenticated)
        }

    @Test
    fun `authenticated navigation to protected screen succeeds immediately`() =
        runTest(timeout = 10.toDuration(DurationUnit.SECONDS)) {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val store = createStore {
                module(AuthModule)
                module(pendAndRedirectModule())
                coroutineContext(dispatcher)
            }
            store.dispatch(AuthAction.Login)
            advanceUntilIdle()

            store.navigation { navigateTo("workspace/home") }
            advanceUntilIdle()

            val state = store.selectState<NavigationState>().first()
            assertEquals("home", state.currentEntry.route)
            assertNull(state.pendingNavigation)
        }

    @Test
    fun `reject guard leaves navigation state unchanged no pending stored`() =
        runTest(timeout = 10.toDuration(DurationUnit.SECONDS)) {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val store = createStore {
                module(AuthModule)
                module(rejectGuardModule())
                coroutineContext(dispatcher)
            }
            advanceUntilIdle()

            store.navigation { navigateTo("workspace/home") }
            advanceUntilIdle()

            val state = store.selectState<NavigationState>().first()
            assertEquals("splash", state.currentEntry.route)
            assertNull(state.pendingNavigation)
        }

    @Test
    fun `authenticated deep link to nested workspace route synthesizes backstack`() =
        runTest(timeout = 10.toDuration(DurationUnit.SECONDS)) {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val store = createStore {
                module(AuthModule)
                module(pendAndRedirectModule())
                coroutineContext(dispatcher)
            }
            store.dispatch(AuthAction.Login)
            advanceUntilIdle()

            // Deep link to tasks/{taskId} — home is the graph entry point so it should
            // appear as a synthesized intermediate entry below the destination.
            store.navigateDeepLink("workspace/tasks/{taskId}?taskId=DEEP-1")
            advanceUntilIdle()

            val state = store.selectState<NavigationState>().first()
            assertEquals("tasks/{taskId}", state.currentEntry.route)
            assertEquals("DEEP-1", state.currentEntry.params["taskId"] as? String)
            assertTrue(state.backStack.size > 1, "Deep link must synthesize the home entry below tasks")
        }

    @Test
    fun `unauthenticated deep link stores pending with route then resume synthesizes backstack`() =
        runTest(timeout = 10.toDuration(DurationUnit.SECONDS)) {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val store = createStore {
                module(AuthModule)
                module(pendAndRedirectModule())
                coroutineContext(dispatcher)
            }
            advanceUntilIdle()

            // Deep link to a sub-screen so synthesis produces home as an intermediate entry.
            store.navigateDeepLink("workspace/tasks/{taskId}?taskId=PENDING-99")
            advanceUntilIdle()

            val afterDeepLink = store.selectState<NavigationState>().first()
            assertEquals("login", afterDeepLink.currentEntry.route)
            assertNotNull(afterDeepLink.pendingNavigation)
            assertEquals("workspace/tasks/{taskId}", afterDeepLink.pendingNavigation!!.route)
            assertEquals("PENDING-99", afterDeepLink.pendingNavigation!!.params["taskId"] as? String)

            store.dispatch(AuthAction.Login)
            advanceUntilIdle()
            store.resumePendingNavigation()
            advanceUntilIdle()

            val afterResume = store.selectState<NavigationState>().first()
            assertEquals("tasks/{taskId}", afterResume.currentEntry.route)
            assertEquals("PENDING-99", afterResume.currentEntry.params["taskId"] as? String)
            assertNull(afterResume.pendingNavigation)
            assertTrue(afterResume.backStack.size > 1, "Resumed deep link must synthesize home entry below tasks")
        }

    @Test
    fun `unauthenticated deep link with params stores params in pending then resume delivers them`() =
        runTest(timeout = 10.toDuration(DurationUnit.SECONDS)) {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val store = createStore {
                module(AuthModule)
                module(pendAndRedirectModule())
                coroutineContext(dispatcher)
            }
            advanceUntilIdle()

            store.navigateDeepLink("workspace/tasks/{taskId}?taskId=TASK-42")
            advanceUntilIdle()

            val pending = store.selectState<NavigationState>().first().pendingNavigation
            assertNotNull(pending)
            assertEquals("TASK-42", pending!!.params["taskId"] as? String)

            store.dispatch(AuthAction.Login)
            advanceUntilIdle()
            store.resumePendingNavigation()
            advanceUntilIdle()

            val afterResume = store.selectState<NavigationState>().first()
            assertEquals("tasks/{taskId}", afterResume.currentEntry.route)
            assertEquals("TASK-42", afterResume.currentEntry.params["taskId"] as? String)
        }

    @Test
    fun `modal opens over workspace navigating away then back restores modal context`() =
        runTest(timeout = 10.toDuration(DurationUnit.SECONDS)) {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val store = createStore {
                module(AuthModule)
                module(pendAndRedirectModule())
                coroutineContext(dispatcher)
            }
            store.dispatch(AuthAction.Login)
            advanceUntilIdle()

            store.navigation { navigateTo("workspace/home") }
            advanceUntilIdle()
            assertEquals("home", store.selectState<NavigationState>().first().currentEntry.route)

            store.navigation { navigateTo("notification") }
            advanceUntilIdle()
            val withModal = store.selectState<NavigationState>().first()
            assertEquals("notification", withModal.currentEntry.route)
            assertTrue(withModal.isCurrentModal)
            assertEquals("home", withModal.underlyingScreen?.route)

            store.navigation { navigateTo("workspace/settings") }
            advanceUntilIdle()
            val atSettings = store.selectState<NavigationState>().first()
            assertEquals("settings", atSettings.currentEntry.route)
            assertFalse(atSettings.isCurrentModal)

            store.navigateBack()
            advanceUntilIdle()
            val afterBack = store.selectState<NavigationState>().first()
            assertEquals("notification", afterBack.currentEntry.route, "Back from settings must restore modal")
            assertTrue(afterBack.isCurrentModal)
            assertEquals("home", afterBack.underlyingScreen?.route)
        }

    @Test
    fun `dismissModals during navigation permanently removes modal back does not restore it`() =
        runTest(timeout = 10.toDuration(DurationUnit.SECONDS)) {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val store = createStore {
                module(AuthModule)
                module(pendAndRedirectModule())
                coroutineContext(dispatcher)
            }
            store.dispatch(AuthAction.Login)
            advanceUntilIdle()

            store.navigation { navigateTo("workspace/home") }
            advanceUntilIdle()

            store.navigation { navigateTo("notification") }
            advanceUntilIdle()
            assertTrue(store.selectState<NavigationState>().first().isCurrentModal)

            store.navigation {
                navigateTo("workspace/settings")
                dismissModals()
            }
            advanceUntilIdle()
            val atSettings = store.selectState<NavigationState>().first()
            assertEquals("settings", atSettings.currentEntry.route)
            assertTrue(atSettings.activeModalContexts.isEmpty(), "Modal contexts must be cleared by dismissModals")

            store.navigateBack()
            advanceUntilIdle()
            val afterBack = store.selectState<NavigationState>().first()
            assertEquals("home", afterBack.currentEntry.route, "Must return to home, not restore modal")
            assertFalse(afterBack.isCurrentModal, "Modal must not be restored after explicit dismissModals")
            assertTrue(afterBack.activeModalContexts.isEmpty())
        }

    @Test
    fun `navigating within protected workspace builds proper backstack and back navigates correctly`() =
        runTest(timeout = 10.toDuration(DurationUnit.SECONDS)) {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val store = createStore {
                module(AuthModule)
                module(pendAndRedirectModule())
                coroutineContext(dispatcher)
            }
            store.dispatch(AuthAction.Login)
            advanceUntilIdle()

            store.navigation { navigateTo("workspace/home") }
            advanceUntilIdle()
            val atHome = store.selectState<NavigationState>().first()
            assertEquals("home", atHome.currentEntry.route)
            val stackSizeAtHome = atHome.backStack.size

            store.navigation {
                params(Params.of("taskId" to "TASK-99"))
                navigateTo("workspace/tasks/{taskId}")
            }
            advanceUntilIdle()
            val atTask = store.selectState<NavigationState>().first()
            assertEquals("tasks/{taskId}", atTask.currentEntry.route)
            assertEquals("TASK-99", atTask.currentEntry.params["taskId"] as? String)
            assertEquals(stackSizeAtHome + 1, atTask.backStack.size)

            store.navigateBack()
            advanceUntilIdle()
            assertEquals("home", store.selectState<NavigationState>().first().currentEntry.route)
        }

    @Test
    fun `logout while on protected screen should allow re-navigation back to protected with pending`() =
        runTest(timeout = 10.toDuration(DurationUnit.SECONDS)) {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val store = createStore {
                module(AuthModule)
                module(pendAndRedirectModule())
                coroutineContext(dispatcher)
            }
            store.dispatch(AuthAction.Login)
            advanceUntilIdle()

            store.navigation { navigateTo("workspace/home") }
            advanceUntilIdle()
            assertEquals("home", store.selectState<NavigationState>().first().currentEntry.route)

            store.dispatch(AuthAction.Logout)
            advanceUntilIdle()

            store.navigation { navigateTo("workspace/settings") }
            advanceUntilIdle()
            val afterLogoutNav = store.selectState<NavigationState>().first()
            assertEquals("login", afterLogoutNav.currentEntry.route)
            assertNotNull(afterLogoutNav.pendingNavigation)
            assertEquals("workspace/settings", afterLogoutNav.pendingNavigation!!.route)

            store.dispatch(AuthAction.Login)
            advanceUntilIdle()
            store.resumePendingNavigation()
            advanceUntilIdle()

            val afterReLogin = store.selectState<NavigationState>().first()
            assertEquals("settings", afterReLogin.currentEntry.route)
            assertNull(afterReLogin.pendingNavigation)
        }

    @Test
    fun `full end-to-end journey unauthenticated to protected auth flow resume modal and navigation`() =
        runTest(timeout = 10.toDuration(DurationUnit.SECONDS)) {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val store = createStore {
                module(AuthModule)
                module(pendAndRedirectModule(displayHint = "Sign in to access your workspace"))
                coroutineContext(dispatcher)
            }
            advanceUntilIdle()

            // Phase 1: Unauthenticated user attempts to reach protected content
            assertEquals("splash", store.selectState<NavigationState>().first().currentEntry.route)
            store.navigation { navigateTo("workspace/home") }
            advanceUntilIdle()

            val atLogin = store.selectState<NavigationState>().first()
            assertEquals("login", atLogin.currentEntry.route)
            assertEquals("workspace/home", atLogin.pendingNavigation?.route)
            assertEquals("Sign in to access your workspace", atLogin.pendingNavigation?.displayHint)

            // Phase 2: User navigates through auth screens (pending persists throughout)
            store.navigation { navigateTo("check-email") }
            advanceUntilIdle()
            assertNotNull(store.selectState<NavigationState>().first().pendingNavigation)

            store.navigation { navigateTo("register") }
            advanceUntilIdle()
            assertNotNull(store.selectState<NavigationState>().first().pendingNavigation)

            // Phase 3: Auth completes, resume pending navigation
            store.dispatch(AuthAction.Login)
            advanceUntilIdle()
            store.resumePendingNavigation()
            advanceUntilIdle()

            val atWorkspace = store.selectState<NavigationState>().first()
            assertEquals("home", atWorkspace.currentEntry.route)
            assertNull(atWorkspace.pendingNavigation)
            assertFalse(atWorkspace.isCurrentModal)

            // Phase 4: Open notification modal from workspace
            store.navigation { navigateTo("notification") }
            advanceUntilIdle()

            val withModal = store.selectState<NavigationState>().first()
            assertEquals("notification", withModal.currentEntry.route)
            assertTrue(withModal.isCurrentModal)
            assertEquals("home", withModal.underlyingScreen?.route)

            // Phase 5: Dismiss modal by going back
            store.navigateBack()
            advanceUntilIdle()
            val backAtWorkspace = store.selectState<NavigationState>().first()
            assertEquals("home", backAtWorkspace.currentEntry.route)
            assertFalse(backAtWorkspace.isCurrentModal)
            assertNull(backAtWorkspace.pendingNavigation)
        }
}
