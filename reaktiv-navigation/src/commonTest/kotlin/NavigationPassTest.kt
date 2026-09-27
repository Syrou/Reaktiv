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
import kotlin.test.assertNotNull
import kotlin.time.DurationUnit
import kotlin.time.toDuration

@OptIn(ExperimentalCoroutinesApi::class)
class NavigationPassTest {

    private fun screen(route: String) = object : Screen {
        override val route = route
        override val enterTransition = NavTransition.None
        override val exitTransition = NavTransition.None

        @Composable
        override fun Content(params: Params) { Text(route) }
    }

    private val splash = screen("splash")
    private val login = screen("login")
    private val dashboard = screen("dashboard")
    private val details = screen("details")
    private val panel = screen("panel")

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

    private fun openModule() = createNavigationModule {
        rootGraph {
            start(splash)
            screens(splash, login)
            graph("workspace") {
                start(route = { _ -> dashboard })
                screens(dashboard, details)
            }
            intercept(guard = { _ -> GuardResult.RedirectTo("workspace") }) {
                graph("admin") {
                    start(panel)
                    screens(panel)
                }
            }
        }
    }

    private fun protectedModule() = createNavigationModule {
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

    private fun paths(state: NavigationState) = state.backStack.map { it.path }

    @Test
    fun aSecondNavigateToADynamicGraphLandsOnItsSelectedStart() =
        runTest(timeout = 10.toDuration(DurationUnit.SECONDS)) {
            val store = createStore {
                module(openModule())
                coroutineContext(StandardTestDispatcher(testScheduler))
            }
            advanceUntilIdle()

            store.navigation {
                navigateTo("login")
                navigateTo("workspace")
            }
            advanceUntilIdle()

            assertEquals(
                listOf("splash", "login", "workspace/dashboard"),
                paths(store.selectState<NavigationState>().first())
            )
        }

    @Test
    fun aGuardRedirectToADynamicGraphLandsOnItsSelectedStart() =
        runTest(timeout = 10.toDuration(DurationUnit.SECONDS)) {
            val store = createStore {
                module(openModule())
                coroutineContext(StandardTestDispatcher(testScheduler))
            }
            advanceUntilIdle()

            store.navigation { navigateTo("admin/panel") }
            advanceUntilIdle()

            assertEquals(
                listOf("splash", "workspace/dashboard"),
                paths(store.selectState<NavigationState>().first())
            )
        }

    @Test
    fun resumingAPendingNavigationAsksTheGuardAgain() =
        runTest(timeout = 10.toDuration(DurationUnit.SECONDS)) {
            val store = createStore {
                module(AuthModule)
                module(protectedModule())
                coroutineContext(StandardTestDispatcher(testScheduler))
            }
            advanceUntilIdle()

            store.navigation { navigateTo("workspace/details") }
            advanceUntilIdle()

            store.navigation {
                clearBackStack()
                resumePendingNavigation()
            }
            advanceUntilIdle()

            val refused = store.selectState<NavigationState>().first()
            assertEquals(listOf("splash", "login"), paths(refused))
            assertNotNull(refused.pendingNavigation)

            store.dispatch(AuthAction.Login)
            advanceUntilIdle()
            store.navigation {
                clearBackStack()
                resumePendingNavigation()
            }
            advanceUntilIdle()

            assertEquals(
                listOf("splash", "workspace/dashboard", "workspace/details"),
                paths(store.selectState<NavigationState>().first())
            )
        }

    @Test
    fun synthesizingOntoAnOccupiedStackKeepsTheRootStartOut() =
        runTest(timeout = 10.toDuration(DurationUnit.SECONDS)) {
            val store = createStore {
                module(openModule())
                coroutineContext(StandardTestDispatcher(testScheduler))
            }
            advanceUntilIdle()

            store.navigation {
                clearBackStack()
                navigateTo("login")
            }
            advanceUntilIdle()

            store.navigation { navigateTo("workspace/details", synthesizeBackstack = true) }
            advanceUntilIdle()

            assertEquals(
                listOf("login", "workspace/dashboard", "workspace/details"),
                paths(store.selectState<NavigationState>().first())
            )
        }
}
