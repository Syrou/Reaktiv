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
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.extension.navigateBack
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.DurationUnit
import kotlin.time.toDuration

@OptIn(ExperimentalCoroutinesApi::class)
class GuardCacheTest {

    private fun screen(route: String) = object : Screen {
        override val route = route
        override val enterTransition = NavTransition.None
        override val exitTransition = NavTransition.None

        @Composable
        override fun Content(params: Params) { Text(route) }
    }

    private val startScreen = screen("start")
    private val loginScreen = screen("login")
    private val homeScreen = screen("home")
    private val pickerScreen = screen("picker")
    private val sideScreen = screen("side")
    private val premiumScreen = screen("premium")
    private val releasesScreen = screen("releases")
    private val noContentScreen = screen("no-content")

    @Serializable
    data class AuthState(
        val isAuthenticated: Boolean = false,
        val premiumAccess: Boolean = false
    ) : ModuleState

    sealed class AuthAction(tag: kotlin.reflect.KClass<*>) : ModuleAction(tag) {
        data object Login : AuthAction(AuthModule::class)
        data object Logout : AuthAction(AuthModule::class)
        data object GrantPremium : AuthAction(AuthModule::class)
    }

    object AuthModule : Module<AuthState, AuthAction> {
        override val initialState = AuthState()
        override val reducer: (AuthState, AuthAction) -> AuthState = { state, action ->
            when (action) {
                AuthAction.Login -> state.copy(isAuthenticated = true)
                AuthAction.Logout -> state.copy(isAuthenticated = false)
                AuthAction.GrantPremium -> state.copy(premiumAccess = true)
            }
        }
        override val createLogic: (StoreAccessor) -> ModuleLogic =
            { object : ModuleLogic() {} }
    }

    @Serializable
    data class ContentState(val hasReleases: Boolean = false) : ModuleState

    sealed class ContentAction(tag: kotlin.reflect.KClass<*>) : ModuleAction(tag) {
        data object AddReleases : ContentAction(ContentModule::class)
    }

    object ContentModule : Module<ContentState, ContentAction> {
        override val initialState = ContentState()
        override val reducer: (ContentState, ContentAction) -> ContentState = { state, action ->
            when (action) {
                ContentAction.AddReleases -> state.copy(hasReleases = true)
            }
        }
        override val createLogic: (StoreAccessor) -> ModuleLogic =
            { object : ModuleLogic() {} }
    }

    private fun cachedGuardModule(evaluations: MutableList<Boolean>) = createNavigationModule {
        rootGraph {
            start(startScreen)
            screens(startScreen, loginScreen)
            intercept(
                guard = { store ->
                    val authenticated = store.selectState<AuthState>().value.isAuthenticated
                    evaluations.add(authenticated)
                    if (authenticated) GuardResult.Allow
                    else GuardResult.RedirectTo(loginScreen)
                },
                cacheKey = { store -> store.selectState<AuthState>().value.isAuthenticated }
            ) {
                graph("workspace") {
                    start(homeScreen)
                    screens(homeScreen)
                }
            }
        }
    }

    private fun uncachedGuardModule(evaluations: MutableList<Boolean>) = createNavigationModule {
        rootGraph {
            start(startScreen)
            screens(startScreen, loginScreen)
            intercept(
                guard = { store ->
                    val authenticated = store.selectState<AuthState>().value.isAuthenticated
                    evaluations.add(authenticated)
                    if (authenticated) GuardResult.Allow
                    else GuardResult.RedirectTo(loginScreen)
                }
            ) {
                graph("workspace") {
                    start(homeScreen)
                    screens(homeScreen, pickerScreen)
                }
                graph("sidegraph") {
                    start(sideScreen)
                    screens(sideScreen)
                }
            }
        }
    }

    private fun chainedGuardModule(
        outerEvaluations: MutableList<Boolean>,
        innerEvaluations: MutableList<Boolean>
    ) = createNavigationModule {
        rootGraph {
            start(startScreen)
            screens(startScreen, loginScreen)
            intercept(
                guard = { store ->
                    val authenticated = store.selectState<AuthState>().value.isAuthenticated
                    outerEvaluations.add(authenticated)
                    if (authenticated) GuardResult.Allow
                    else GuardResult.RedirectTo(loginScreen)
                },
                cacheKey = { store -> store.selectState<AuthState>().value.isAuthenticated }
            ) {
                graph("workspace") {
                    start(homeScreen)
                    screens(homeScreen)
                }
                intercept(
                    guard = { store ->
                        val premium = store.selectState<AuthState>().value.premiumAccess
                        innerEvaluations.add(premium)
                        if (premium) GuardResult.Allow
                        else GuardResult.RedirectTo(loginScreen)
                    },
                    cacheKey = { store -> store.selectState<AuthState>().value.premiumAccess }
                ) {
                    graph("premium-zone") {
                        start(premiumScreen)
                        screens(premiumScreen)
                    }
                }
            }
        }
    }

    private fun cachedEntryModule(evaluations: MutableList<Boolean>) = createNavigationModule {
        rootGraph {
            start(startScreen)
            screens(startScreen)
            graph("content") {
                start(
                    route = { store ->
                        val hasReleases = store.selectState<ContentState>().value.hasReleases
                        evaluations.add(hasReleases)
                        if (hasReleases) releasesScreen else noContentScreen
                    },
                    cacheKey = { store -> store.selectState<ContentState>().value.hasReleases }
                )
                screens(releasesScreen, noContentScreen)
            }
        }
    }

    @Test
    fun `guard with cache key is not re-evaluated while key is unchanged`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val evaluations = mutableListOf<Boolean>()
            val dispatcher = StandardTestDispatcher(testScheduler)
            val store = createStore {
                module(AuthModule)
                module(cachedGuardModule(evaluations))
                coroutineContext(dispatcher)
            }
            store.dispatch(AuthAction.Login)
            advanceUntilIdle()

            store.navigation { navigateTo("workspace") }
            advanceUntilIdle()
            assertEquals("home", store.selectState<NavigationState>().first().currentEntry.route)
            assertEquals(1, evaluations.size)

            store.navigateBack()
            advanceUntilIdle()
            assertEquals("start", store.selectState<NavigationState>().first().currentEntry.route)

            store.navigation { navigateTo("workspace") }
            advanceUntilIdle()
            assertEquals("home", store.selectState<NavigationState>().first().currentEntry.route)
            assertEquals(1, evaluations.size)
        }

    @Test
    fun `guard with cache key is re-evaluated when key changes`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val evaluations = mutableListOf<Boolean>()
            val dispatcher = StandardTestDispatcher(testScheduler)
            val store = createStore {
                module(AuthModule)
                module(cachedGuardModule(evaluations))
                coroutineContext(dispatcher)
            }
            advanceUntilIdle()

            store.navigation { navigateTo("workspace") }
            advanceUntilIdle()
            assertEquals("login", store.selectState<NavigationState>().first().currentEntry.route)
            assertEquals(listOf(false), evaluations)

            store.dispatch(AuthAction.Login)
            advanceUntilIdle()
            store.navigation { navigateTo("workspace") }
            advanceUntilIdle()
            assertEquals("home", store.selectState<NavigationState>().first().currentEntry.route)
            assertEquals(listOf(false, true), evaluations)

            store.navigateBack()
            advanceUntilIdle()
            store.dispatch(AuthAction.Logout)
            advanceUntilIdle()
            store.navigation { navigateTo("workspace") }
            advanceUntilIdle()
            assertEquals("login", store.selectState<NavigationState>().first().currentEntry.route)
            assertEquals(listOf(false, true, false), evaluations)
        }

    @Test
    fun `navigation between graphs in the same intercept zone does not evaluate the guard`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val evaluations = mutableListOf<Boolean>()
            val dispatcher = StandardTestDispatcher(testScheduler)
            val store = createStore {
                module(AuthModule)
                module(uncachedGuardModule(evaluations))
                coroutineContext(dispatcher)
            }
            store.dispatch(AuthAction.Login)
            advanceUntilIdle()

            store.navigation { navigateTo("workspace") }
            advanceUntilIdle()
            assertEquals("home", store.selectState<NavigationState>().first().currentEntry.route)
            assertEquals(1, evaluations.size)

            store.navigation { navigateTo("sidegraph") }
            advanceUntilIdle()
            assertEquals("side", store.selectState<NavigationState>().first().currentEntry.route)
            assertEquals(1, evaluations.size)

            store.navigation { navigateTo("workspace") }
            advanceUntilIdle()
            assertEquals("home", store.selectState<NavigationState>().first().currentEntry.route)
            assertEquals(1, evaluations.size)
        }

    @Test
    fun `back then navigate to a sibling zone graph lands on the target as two calls`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val evaluations = mutableListOf<Boolean>()
            val dispatcher = StandardTestDispatcher(testScheduler)
            val store = createStore {
                module(AuthModule)
                module(uncachedGuardModule(evaluations))
                coroutineContext(dispatcher)
            }
            store.dispatch(AuthAction.Login)
            advanceUntilIdle()

            store.navigation { navigateTo("workspace") }
            advanceUntilIdle()
            store.navigation { navigateTo("workspace/picker") }
            advanceUntilIdle()
            assertEquals("picker", store.selectState<NavigationState>().first().currentEntry.route)

            store.navigateBack()
            store.navigation { navigateTo("sidegraph") }
            advanceUntilIdle()

            val state = store.selectState<NavigationState>().first()
            assertEquals("side", state.currentEntry.route)
            assertEquals(listOf("start", "home", "side"), state.backStack.map { it.navigatable.route })
            assertEquals(1, evaluations.size)
        }

    @Test
    fun `back then navigate to a sibling zone graph lands on the target as one block`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val evaluations = mutableListOf<Boolean>()
            val dispatcher = StandardTestDispatcher(testScheduler)
            val store = createStore {
                module(AuthModule)
                module(uncachedGuardModule(evaluations))
                coroutineContext(dispatcher)
            }
            store.dispatch(AuthAction.Login)
            advanceUntilIdle()

            store.navigation { navigateTo("workspace") }
            advanceUntilIdle()
            store.navigation { navigateTo("workspace/picker") }
            advanceUntilIdle()
            assertEquals("picker", store.selectState<NavigationState>().first().currentEntry.route)

            store.navigation {
                navigateBack()
                navigateTo("sidegraph")
            }
            advanceUntilIdle()

            val state = store.selectState<NavigationState>().first()
            assertEquals("side", state.currentEntry.route)
            assertEquals(listOf("start", "home", "side"), state.backStack.map { it.navigatable.route })
            assertEquals(1, evaluations.size)
        }

    @Test
    fun `guard without cache key is evaluated on every navigation into the zone`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val evaluations = mutableListOf<Boolean>()
            val dispatcher = StandardTestDispatcher(testScheduler)
            val store = createStore {
                module(AuthModule)
                module(uncachedGuardModule(evaluations))
                coroutineContext(dispatcher)
            }
            store.dispatch(AuthAction.Login)
            advanceUntilIdle()

            store.navigation { navigateTo("workspace") }
            advanceUntilIdle()
            store.navigateBack()
            advanceUntilIdle()
            store.navigation { navigateTo("workspace") }
            advanceUntilIdle()

            assertEquals("home", store.selectState<NavigationState>().first().currentEntry.route)
            assertEquals(2, evaluations.size)
        }

    @Test
    fun `outer guard cache is shared between sibling intercept zones`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val outerEvaluations = mutableListOf<Boolean>()
            val innerEvaluations = mutableListOf<Boolean>()
            val dispatcher = StandardTestDispatcher(testScheduler)
            val store = createStore {
                module(AuthModule)
                module(chainedGuardModule(outerEvaluations, innerEvaluations))
                coroutineContext(dispatcher)
            }
            store.dispatch(AuthAction.Login)
            store.dispatch(AuthAction.GrantPremium)
            advanceUntilIdle()

            store.navigation { navigateTo("workspace") }
            advanceUntilIdle()
            assertEquals("home", store.selectState<NavigationState>().first().currentEntry.route)
            assertEquals(1, outerEvaluations.size)
            assertEquals(0, innerEvaluations.size)

            store.navigateBack()
            advanceUntilIdle()

            store.navigation { navigateTo("premium-zone") }
            advanceUntilIdle()
            assertEquals("premium", store.selectState<NavigationState>().first().currentEntry.route)
            assertEquals(1, outerEvaluations.size)
            assertEquals(1, innerEvaluations.size)
        }

    @Test
    fun `entry selector with cache key is not re-evaluated while key is unchanged`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val evaluations = mutableListOf<Boolean>()
            val dispatcher = StandardTestDispatcher(testScheduler)
            val store = createStore {
                module(ContentModule)
                module(cachedEntryModule(evaluations))
                coroutineContext(dispatcher)
            }
            advanceUntilIdle()

            store.navigation { navigateTo("content") }
            advanceUntilIdle()
            assertEquals("no-content", store.selectState<NavigationState>().first().currentEntry.route)
            assertEquals(1, evaluations.size)

            store.navigateBack()
            advanceUntilIdle()
            store.navigation { navigateTo("content") }
            advanceUntilIdle()
            assertEquals("no-content", store.selectState<NavigationState>().first().currentEntry.route)
            assertEquals(1, evaluations.size)

            store.navigateBack()
            advanceUntilIdle()
            store.dispatch(ContentAction.AddReleases)
            advanceUntilIdle()
            store.navigation { navigateTo("content") }
            advanceUntilIdle()
            assertEquals("releases", store.selectState<NavigationState>().first().currentEntry.route)
            assertEquals(2, evaluations.size)
        }

    @Test
    fun `cached guard results are dropped on store reset`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val evaluations = mutableListOf<Boolean>()
            val dispatcher = StandardTestDispatcher(testScheduler)
            val store = createStore {
                module(AuthModule)
                module(cachedGuardModule(evaluations))
                coroutineContext(dispatcher)
            }
            store.dispatch(AuthAction.Login)
            advanceUntilIdle()

            store.navigation { navigateTo("workspace") }
            advanceUntilIdle()
            assertEquals(1, evaluations.size)

            store.reset()
            advanceUntilIdle()

            store.navigation { navigateTo("workspace") }
            advanceUntilIdle()
            assertEquals("login", store.selectState<NavigationState>().first().currentEntry.route)
            assertEquals(listOf(true, false), evaluations)
        }
}
