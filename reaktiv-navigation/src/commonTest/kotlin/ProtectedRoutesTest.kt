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
import io.github.syrou.reaktiv.navigation.definition.LoadingModal
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.extension.navigateDeepLink
import io.github.syrou.reaktiv.navigation.extension.navigation
import io.github.syrou.reaktiv.navigation.extension.resumePendingNavigation
import io.github.syrou.reaktiv.navigation.model.GuardResult
import io.github.syrou.reaktiv.navigation.param.Params
import io.github.syrou.reaktiv.navigation.transition.NavTransition
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.DurationUnit
import kotlin.time.toDuration

@OptIn(ExperimentalCoroutinesApi::class)
class ProtectedRoutesTest {

    private fun screen(route: String) = object : Screen {
        override val route = route
        override val enterTransition = NavTransition.None
        override val exitTransition = NavTransition.None
        override val requiresAuth = false

        @Composable
        override fun Content(params: Params) { Text(route) }
    }

    private fun loadingModal(route: String) = object : LoadingModal {
        override val route = route
        override val enterTransition = NavTransition.None
        override val exitTransition = NavTransition.None
        override val requiresAuth = false

        @Composable
        override fun Content(params: Params) { Text(route) }
    }

    private val startScreen    = screen("start")
    private val homeScreen     = screen("home")
    private val loginScreen    = screen("login")
    private val loadingModal  = loadingModal("loading")
    private val inviteScreen   = screen("invite/{token}")
    private val checkEmail     = screen("check-email")
    private val registerScreen = screen("register")
    private val releasesScreen = screen("releases")
    private val artistScreen   = screen("artists")
    private val noArtistScreen = screen("no-artists")

    @Serializable
    data class AuthState(val isAuthenticated: Boolean = false) : ModuleState

    sealed class AuthAction(tag: kotlin.reflect.KClass<*>) : ModuleAction(tag) {
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

    @Serializable
    data class ContentState(
        val hasReleases: Boolean = false,
        val hasArtists: Boolean = false
    ) : ModuleState

    sealed class ContentAction(tag: kotlin.reflect.KClass<*>) : ModuleAction(tag) {
        data object AddReleases : ContentAction(ContentModule::class)
        data object AddArtists  : ContentAction(ContentModule::class)
    }

    object ContentModule : Module<ContentState, ContentAction> {
        override val initialState = ContentState()
        override val reducer: (ContentState, ContentAction) -> ContentState = { state, action ->
            when (action) {
                ContentAction.AddReleases -> state.copy(hasReleases = true)
                ContentAction.AddArtists  -> state.copy(hasArtists = true)
            }
        }
        override val createLogic: (StoreAccessor) -> ModuleLogic =
            { object : ModuleLogic() {} }
    }

    private fun moduleWithReject() = createNavigationModule {
        rootGraph {
            entry(startScreen)
            screens(startScreen, loginScreen)
            intercept(
                guard = { store ->
                    if (store.selectState<AuthState>().value.isAuthenticated) GuardResult.Allow
                    else GuardResult.Reject
                }
            ) {
                graph("workspace") {
                    entry(homeScreen)
                    screens(homeScreen)
                }
            }
        }
    }

    private fun moduleWithRedirect() = createNavigationModule {
        rootGraph {
            entry(startScreen)
            screens(startScreen, loginScreen)
            intercept(
                guard = { store ->
                    if (store.selectState<AuthState>().value.isAuthenticated) GuardResult.Allow
                    else GuardResult.RedirectTo(loginScreen)
                }
            ) {
                graph("workspace") {
                    entry(homeScreen)
                    screens(homeScreen)
                }
            }
        }
    }

    private fun moduleWithPendAndRedirect(displayHint: String? = null) = createNavigationModule {
        rootGraph {
            entry(startScreen)
            screens(startScreen, loginScreen, checkEmail, registerScreen)
            intercept(
                guard = { store ->
                    if (store.selectState<AuthState>().value.isAuthenticated) GuardResult.Allow
                    else GuardResult.PendAndRedirectTo(
                        navigatable = loginScreen,
                        metadata = mapOf("source" to "protected-route"),
                        displayHint = displayHint
                    )
                }
            ) {
                graph("workspace") {
                    entry(homeScreen)
                    screens(homeScreen, inviteScreen)
                }
            }
        }
    }

    private fun moduleWithDeepLinkAlias() = createNavigationModule {
        rootGraph {
            entry(startScreen)
            screens(startScreen, loginScreen, checkEmail, registerScreen)
            intercept(
                guard = { store ->
                    if (store.selectState<AuthState>().value.isAuthenticated) GuardResult.Allow
                    else GuardResult.PendAndRedirectTo(
                        navigatable = loginScreen,
                        displayHint = "You have a pending invite"
                    )
                }
            ) {
                graph("workspace") {
                    entry(homeScreen)
                    screens(homeScreen, inviteScreen)
                }
            }
        }
        deepLinkAliases {
            alias(
                pattern = "artist/invite",
                targetRoute = "workspace/invite/{token}"
            ) { params ->
                Params.of("token" to (params["token"] as? String ?: ""))
            }
        }
    }

    private fun moduleWithLoadingScreen() = createNavigationModule {
        loadingModal(loadingModal)
        rootGraph {
            entry(startScreen)
            screens(startScreen, loginScreen)
            intercept(
                guard = { store ->
                    if (store.selectState<AuthState>().value.isAuthenticated) GuardResult.Allow
                    else GuardResult.Reject
                }
            ) {
                graph("workspace") {
                    entry(homeScreen)
                    screens(homeScreen)
                }
            }
        }
    }

    @Test
    fun `deep link alias remaps old path to canonical route`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val store = createStore {
                module(AuthModule)
                module(moduleWithDeepLinkAlias())
                coroutineContext(dispatcher)
            }
            store.dispatch(AuthAction.Login)
            advanceUntilIdle()

            store.navigateDeepLink("artist/invite?token=abc123")
            advanceUntilIdle()

            val state = store.selectState<NavigationState>().first()
            assertEquals("invite/{token}", state.currentEntry.route)
            assertEquals("abc123", state.currentEntry.params["token"] as? String)
        }

    @Test
    fun `deep link alias resolves params via mapping function`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val store = createStore {
                module(AuthModule)
                module(moduleWithDeepLinkAlias())
                coroutineContext(dispatcher)
            }
            store.dispatch(AuthAction.Login)
            advanceUntilIdle()

            store.navigateDeepLink("artist/invite?token=XYZ789")
            advanceUntilIdle()

            val state = store.selectState<NavigationState>().first()
            assertEquals("XYZ789", state.currentEntry.params["token"] as? String)
        }

    @Test
    fun `deep link without matching alias passes through unchanged`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val store = createStore {
                module(AuthModule)
                module(moduleWithDeepLinkAlias())
                coroutineContext(dispatcher)
            }
            store.dispatch(AuthAction.Login)
            advanceUntilIdle()

            store.navigateDeepLink("workspace/home")
            advanceUntilIdle()

            val state = store.selectState<NavigationState>().first()
            assertEquals("home", state.currentEntry.route)
        }

    @Test
    fun `guard allows authenticated user to reach protected route`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val store = createStore {
                module(AuthModule)
                module(moduleWithReject())
                coroutineContext(dispatcher)
            }
            store.dispatch(AuthAction.Login)
            advanceUntilIdle()

            store.navigation { navigateTo("workspace/home") }
            advanceUntilIdle()

            val state = store.selectState<NavigationState>().first()
            assertEquals("home", state.currentEntry.route)
        }

    @Test
    fun `guard denies with Reject navigation is silently dropped`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val store = createStore {
                module(AuthModule)
                module(moduleWithReject())
                coroutineContext(dispatcher)
            }

            store.navigation { navigateTo("workspace/home") }
            advanceUntilIdle()

            val state = store.selectState<NavigationState>().first()
            assertEquals("start", state.currentEntry.route)
            assertNull(state.pendingNavigation)
        }

    @Test
    fun `guard denies with RedirectTo user redirected no pending state stored`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val store = createStore {
                module(AuthModule)
                module(moduleWithRedirect())
                coroutineContext(dispatcher)
            }

            store.navigation { navigateTo("workspace/home") }
            advanceUntilIdle()

            val state = store.selectState<NavigationState>().first()
            assertEquals("login", state.currentEntry.route)
            assertNull(state.pendingNavigation)
        }

    @Test
    fun `guard denies with PendAndRedirectTo pending navigation stored with correct route`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val store = createStore {
                module(AuthModule)
                module(moduleWithPendAndRedirect())
                coroutineContext(dispatcher)
            }

            store.navigation { navigateTo("workspace/home") }
            advanceUntilIdle()

            val state = store.selectState<NavigationState>().first()
            assertEquals("login", state.currentEntry.route)
            assertNotNull(state.pendingNavigation)
            assertEquals("workspace/home", state.pendingNavigation!!.route)
        }

    @Test
    fun `guard denies with PendAndRedirectTo metadata is preserved`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val store = createStore {
                module(AuthModule)
                module(moduleWithPendAndRedirect())
                coroutineContext(dispatcher)
            }

            store.navigation { navigateTo("workspace/home") }
            advanceUntilIdle()

            val pending = store.selectState<NavigationState>().first().pendingNavigation
            assertNotNull(pending)
            assertEquals("protected-route", pending!!.metadata["source"])
        }

    @Test
    fun `displayHint is preserved in pending navigation`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val store = createStore {
                module(AuthModule)
                module(moduleWithPendAndRedirect(displayHint = "You have a pending invite to join an artist team"))
                coroutineContext(dispatcher)
            }

            store.navigation { navigateTo("workspace/home") }
            advanceUntilIdle()

            val pending = store.selectState<NavigationState>().first().pendingNavigation
            assertNotNull(pending)
            assertEquals("You have a pending invite to join an artist team", pending!!.displayHint)
        }

    @Test
    fun `screen nested inside intercepted graph inherits the guard`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val store = createStore {
                module(AuthModule)
                module(moduleWithPendAndRedirect())
                coroutineContext(dispatcher)
            }

            store.navigation {
                params(Params.of("token" to "abc"))
                navigateTo("workspace/invite/{token}")
            }
            advanceUntilIdle()

            val state = store.selectState<NavigationState>().first()
            assertEquals("login", state.currentEntry.route)
            assertNotNull(state.pendingNavigation)
            assertEquals("workspace/invite/{token}", state.pendingNavigation!!.route)
        }

    @Test
    fun `resumePendingNavigation navigates to pending route and clears it`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val store = createStore {
                module(AuthModule)
                module(moduleWithPendAndRedirect())
                coroutineContext(dispatcher)
            }

            store.navigation { navigateTo("workspace/home") }
            advanceUntilIdle()

            assertNotNull(store.selectState<NavigationState>().first().pendingNavigation)

            store.dispatch(AuthAction.Login)
            advanceUntilIdle()
            store.resumePendingNavigation()
            advanceUntilIdle()

            val state = store.selectState<NavigationState>().first()
            assertEquals("home", state.currentEntry.route)
            assertNull(state.pendingNavigation)
        }

    @Test
    fun `resumePendingNavigation is no-op when no pending navigation`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val store = createStore {
                module(AuthModule)
                module(moduleWithPendAndRedirect())
                coroutineContext(dispatcher)
            }

            store.resumePendingNavigation()
            advanceUntilIdle()

            val state = store.selectState<NavigationState>().first()
            assertEquals("start", state.currentEntry.route)
        }

    @Test
    fun `guard with loadingScreen loading screen NOT shown when guard resolves below threshold`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val store = createStore {
                module(AuthModule)
                module(moduleWithLoadingScreen())
                coroutineContext(dispatcher)
            }

            store.navigation { navigateTo("workspace/home") }
            advanceUntilIdle()

            // Guard resolves immediately (faster than threshold) → loading screen never shown
            // Guard returns Reject → navigation silently dropped
            val state = store.selectState<NavigationState>().first()
            assertEquals("start", state.currentEntry.route)
        }

    @Test
    fun `guard with loadingScreen navigates to target when guard passes`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val store = createStore {
                module(AuthModule)
                module(moduleWithLoadingScreen())
                coroutineContext(dispatcher)
            }
            store.dispatch(AuthAction.Login)
            advanceUntilIdle()

            store.navigation { navigateTo("workspace/home") }
            advanceUntilIdle()

            val state = store.selectState<NavigationState>().first()
            assertEquals("home", state.currentEntry.route)
        }

    @Test
    fun `loading screen shown while guard suspends routes to target when guard allows`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val guardGate = CompletableDeferred<GuardResult>()

            val navModule = createNavigationModule {
                loadingModal(loadingModal)
                rootGraph {
                    entry(startScreen)
                    screens(startScreen)
                    intercept(
                        guard = { guardGate.await() }
                    ) {
                        graph("workspace") {
                            entry(homeScreen)
                            screens(homeScreen)
                        }
                    }
                }
            }

            val store = createStore {
                module(AuthModule)
                module(navModule)
                coroutineContext(dispatcher)
            }

            launch { store.navigation { navigateTo("workspace/home") } }
            advanceUntilIdle()

            val duringGuard = store.selectState<NavigationState>().first()
            assertEquals("loading", duringGuard.currentEntry.route)

            guardGate.complete(GuardResult.Allow)
            advanceUntilIdle()

            val afterGuard = store.selectState<NavigationState>().first()
            assertEquals("home", afterGuard.currentEntry.route)
        }

    @Test
    fun `loading screen shown while guard suspends redirected when guard denies`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val guardGate = CompletableDeferred<GuardResult>()

            val navModule = createNavigationModule {
                loadingModal(loadingModal)
                rootGraph {
                    entry(startScreen)
                    screens(startScreen, loginScreen)
                    intercept(
                        guard = { guardGate.await() }
                    ) {
                        graph("workspace") {
                            entry(homeScreen)
                            screens(homeScreen)
                        }
                    }
                }
            }

            val store = createStore {
                module(AuthModule)
                module(navModule)
                coroutineContext(dispatcher)
            }

            launch { store.navigation { navigateTo("workspace/home") } }
            advanceUntilIdle()

            val duringGuard = store.selectState<NavigationState>().first()
            assertEquals("loading", duringGuard.currentEntry.route)

            guardGate.complete(GuardResult.RedirectTo(loginScreen))
            advanceUntilIdle()

            val afterGuard = store.selectState<NavigationState>().first()
            assertEquals("login", afterGuard.currentEntry.route)
        }

    @Test
    fun `dispatching ClearPendingNavigation removes pending navigation from state`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val store = createStore {
                module(AuthModule)
                module(moduleWithPendAndRedirect())
                coroutineContext(dispatcher)
            }

            store.navigation { navigateTo("workspace/home") }
            advanceUntilIdle()

            assertNotNull(store.selectState<NavigationState>().first().pendingNavigation)

            store.dispatch(NavigationAction.ClearPendingNavigation)
            advanceUntilIdle()

            assertNull(store.selectState<NavigationState>().first().pendingNavigation)
        }

    @Test
    fun `pending navigation persists while user navigates through the auth flow`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val store = createStore {
                module(AuthModule)
                module(moduleWithPendAndRedirect(displayHint = "Pending invite"))
                coroutineContext(dispatcher)
            }

            store.navigation { navigateTo("workspace/home") }
            advanceUntilIdle()

            assertEquals("login", store.selectState<NavigationState>().first().currentEntry.route)

            store.navigation { navigateTo("check-email") }
            advanceUntilIdle()

            val atCheckEmail = store.selectState<NavigationState>().first()
            assertEquals("check-email", atCheckEmail.currentEntry.route)
            assertNotNull(atCheckEmail.pendingNavigation)
            assertEquals("Pending invite", atCheckEmail.pendingNavigation!!.displayHint)

            store.navigation { navigateTo("register") }
            advanceUntilIdle()

            val atRegister = store.selectState<NavigationState>().first()
            assertEquals("register", atRegister.currentEntry.route)
            assertNotNull(atRegister.pendingNavigation)
        }

    @Test
    fun `full artist-invite flow via deep link alias through registration`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val store = createStore {
                module(AuthModule)
                module(moduleWithDeepLinkAlias())
                coroutineContext(dispatcher)
            }

            store.navigateDeepLink("artist/invite?token=INVITE_TOKEN")
            advanceUntilIdle()

            val afterAlias = store.selectState<NavigationState>().first()
            assertEquals("login", afterAlias.currentEntry.route)
            assertNotNull(afterAlias.pendingNavigation)
            assertEquals("workspace/invite/{token}", afterAlias.pendingNavigation!!.route)
            assertEquals("INVITE_TOKEN", afterAlias.pendingNavigation!!.params["token"] as? String)
            assertEquals("You have a pending invite", afterAlias.pendingNavigation!!.displayHint)

            store.navigation { navigateTo("check-email") }
            advanceUntilIdle()
            assertNotNull(store.selectState<NavigationState>().first().pendingNavigation)

            store.navigation { navigateTo("register") }
            advanceUntilIdle()
            assertNotNull(store.selectState<NavigationState>().first().pendingNavigation)

            store.dispatch(NavigationAction.ClearPendingNavigation)
            advanceUntilIdle()
            assertNull(store.selectState<NavigationState>().first().pendingNavigation)
        }

    @Test
    fun `login path pending survives navigation to intermediate screen then cleared`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val store = createStore {
                module(AuthModule)
                module(moduleWithPendAndRedirect(displayHint = "Join your team"))
                coroutineContext(dispatcher)
            }

            store.navigation { navigateTo("workspace/home") }
            advanceUntilIdle()

            assertNotNull(store.selectState<NavigationState>().first().pendingNavigation)

            store.dispatch(AuthAction.Login)
            advanceUntilIdle()
            store.navigation { navigateTo("start") }
            advanceUntilIdle()

            val atStart = store.selectState<NavigationState>().first()
            assertEquals("start", atStart.currentEntry.route)
            assertNotNull(atStart.pendingNavigation)

            store.dispatch(NavigationAction.ClearPendingNavigation)
            advanceUntilIdle()

            assertNull(store.selectState<NavigationState>().first().pendingNavigation)
        }

    @Test
    fun `entry route navigates to dynamic screen based on state when entering graph directly`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val navModule = createNavigationModule {
                rootGraph {
                    entry(startScreen)
                    screens(startScreen)
                    graph("content") {
                        entry(
                            route = { store ->
                                val state = store.selectState<ContentState>().value
                                when {
                                    state.hasReleases -> releasesScreen
                                    state.hasArtists  -> artistScreen
                                    else              -> noArtistScreen
                                }
                            }
                        )
                        screens(releasesScreen, artistScreen, noArtistScreen)
                    }
                }
            }

            val store = createStore {
                module(ContentModule)
                module(navModule)
                coroutineContext(dispatcher)
            }

            store.dispatch(ContentAction.AddArtists)
            advanceUntilIdle()

            store.navigation { navigateTo("content") }
            advanceUntilIdle()

            val state = store.selectState<NavigationState>().first()
            assertEquals("artists", state.currentEntry.route)
        }

    @Test
    fun `entry route routes to releases screen when releases are present`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val navModule = createNavigationModule {
                rootGraph {
                    entry(startScreen)
                    screens(startScreen)
                    graph("content") {
                        entry(
                            route = { store ->
                                val state = store.selectState<ContentState>().value
                                when {
                                    state.hasReleases -> releasesScreen
                                    state.hasArtists  -> artistScreen
                                    else              -> noArtistScreen
                                }
                            }
                        )
                        screens(releasesScreen, artistScreen, noArtistScreen)
                    }
                }
            }

            val store = createStore {
                module(ContentModule)
                module(navModule)
                coroutineContext(dispatcher)
            }

            store.dispatch(ContentAction.AddReleases)
            advanceUntilIdle()

            store.navigation { navigateTo("content") }
            advanceUntilIdle()

            val state = store.selectState<NavigationState>().first()
            assertEquals("releases", state.currentEntry.route)
        }

    @Test
    fun `entry route routes to fallback when no content`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val navModule = createNavigationModule {
                rootGraph {
                    entry(startScreen)
                    screens(startScreen)
                    graph("content") {
                        entry(
                            route = { store ->
                                val state = store.selectState<ContentState>().value
                                when {
                                    state.hasReleases -> releasesScreen
                                    state.hasArtists  -> artistScreen
                                    else              -> noArtistScreen
                                }
                            }
                        )
                        screens(releasesScreen, artistScreen, noArtistScreen)
                    }
                }
            }

            val store = createStore {
                module(ContentModule)
                module(navModule)
                coroutineContext(dispatcher)
            }

            store.navigation { navigateTo("content") }
            advanceUntilIdle()

            val state = store.selectState<NavigationState>().first()
            assertEquals("no-artists", state.currentEntry.route)
        }

    // ─── intercept wrapping a single graph ───────────────────────────────────

    @Test
    fun `intercept on single graph blocks navigation when guard denies`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val navModule = createNavigationModule {
                rootGraph {
                    entry(startScreen)
                    screens(startScreen, loginScreen)
                    intercept(
                        guard = { store ->
                            val state = store.selectState<ContentState>().value
                            if (state.hasArtists || state.hasReleases) GuardResult.Allow
                            else GuardResult.RedirectTo(loginScreen)
                        }
                    ) {
                        graph("content") {
                            entry(route = { noArtistScreen })
                            screens(releasesScreen, artistScreen, noArtistScreen)
                        }
                    }
                }
            }

            val store = createStore {
                module(ContentModule)
                module(navModule)
                coroutineContext(dispatcher)
            }

            store.navigation { navigateTo("content") }
            advanceUntilIdle()

            val state = store.selectState<NavigationState>().first()
            assertEquals("login", state.currentEntry.route)
        }

    @Test
    fun `intercept on single graph allows navigation and entry route selects screen`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val navModule = createNavigationModule {
                rootGraph {
                    entry(startScreen)
                    screens(startScreen, loginScreen)
                    intercept(
                        guard = { store ->
                            val state = store.selectState<ContentState>().value
                            if (state.hasArtists || state.hasReleases) GuardResult.Allow
                            else GuardResult.RedirectTo(loginScreen)
                        }
                    ) {
                        graph("content") {
                            entry(route = { releasesScreen })
                            screens(releasesScreen, artistScreen, noArtistScreen)
                        }
                    }
                }
            }

            val store = createStore {
                module(ContentModule)
                module(navModule)
                coroutineContext(dispatcher)
            }

            store.dispatch(ContentAction.AddReleases)
            advanceUntilIdle()

            store.navigation { navigateTo("content") }
            advanceUntilIdle()

            val state = store.selectState<NavigationState>().first()
            assertEquals("releases", state.currentEntry.route)
        }
}

