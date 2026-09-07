import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import io.github.syrou.reaktiv.core.Store
import io.github.syrou.reaktiv.core.createStore
import io.github.syrou.reaktiv.core.util.selectState
import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.createNavigationModule
import io.github.syrou.reaktiv.navigation.definition.LoadingModal
import io.github.syrou.reaktiv.navigation.definition.NavigationPath
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.extension.navigateDeepLink
import io.github.syrou.reaktiv.navigation.extension.navigation
import io.github.syrou.reaktiv.navigation.model.GuardResult
import io.github.syrou.reaktiv.navigation.param.Params
import io.github.syrou.reaktiv.navigation.transition.NavTransition
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.DurationUnit
import kotlin.time.toDuration

/**
 * A guard is skipped once the app has passed it, and clearing the back stack does not take that
 * back: what a clear discards is history, not the position inside the zone the app already holds.
 * Two things earn nothing. A request from outside the app makes its own case at the door, and the
 * stack the state is constructed with was never navigated to, so no guard was ever asked about it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GuardZoneReentryTest {

    private enum class RootStart { OutsideZone, DynamicIntoZone, StaticIntoZone }

    private fun screen(route: String) = object : Screen {
        override val route = route
        override val enterTransition = NavTransition.None
        override val exitTransition = NavTransition.None

        @Composable
        override fun Content(params: Params) { Text(route) }
    }

    private val loadingScreen = object : LoadingModal {
        override val route = "loading"
        override val enterTransition = NavTransition.None
        override val exitTransition = NavTransition.None

        @Composable
        override fun Content(params: Params) { Text("loading") }
    }

    private val splash = screen("splash")
    private val login = screen("login")
    private val homeStart = screen("home-start")
    private val workspace = screen("workspace-overview")

    private var guardCalls = 0
    private var guardResult: () -> GuardResult = { GuardResult.Allow }

    private fun module(rootStart: RootStart) = createNavigationModule {
        loadingModal(loadingScreen)
        rootGraph {
            when (rootStart) {
                RootStart.OutsideZone -> start(splash)
                RootStart.DynamicIntoZone -> start(route = { _ -> NavigationPath("home") })
                RootStart.StaticIntoZone -> start("home")
            }
            screens(splash, login)
            intercept(guard = { _ ->
                guardCalls++
                guardResult()
            }) {
                graph("home") {
                    start(homeStart)
                    screens(homeStart)

                    graph("workspace") {
                        start(workspace)
                        screens(workspace)
                    }
                }
            }
        }
        deepLinkAliases {
            alias("app/workspace", "home/workspace/workspace-overview") { Params.empty() }
        }
    }

    private fun withStore(
        rootStart: RootStart = RootStart.OutsideZone,
        initialGuardResult: () -> GuardResult = { GuardResult.Allow },
        block: suspend (Store) -> Unit
    ) = runTest(timeout = 20.toDuration(DurationUnit.SECONDS)) {
        guardCalls = 0
        guardResult = initialGuardResult
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = createStore {
            module(module(rootStart))
            coroutineContext(dispatcher)
        }
        advanceUntilIdle()
        block(store)
        advanceUntilIdle()
    }

    private suspend fun Store.paths(): List<String> =
        selectState<NavigationState>().first().backStack.map { it.path }

    @Test
    fun clearingTheStackFromInsideTheZoneDoesNotAskTheGuardAgain() = withStore { store ->
        store.navigation { navigateTo("home/workspace") }
        assertEquals(1, guardCalls, "Entering the zone must be guarded")

        store.navigation {
            clearBackStack()
            navigateTo("home")
        }

        assertEquals(1, guardCalls, "Resetting history inside the zone must not re-run the guard")
        assertEquals(listOf("home/home-start"), store.paths())
    }

    @Test
    fun clearingTheStackFromOutsideTheZoneStillAsksTheGuard() =
        withStore(initialGuardResult = { GuardResult.RedirectTo("login") }) { store ->
            store.navigation {
                clearBackStack()
                navigateTo("home")
            }

            assertEquals(1, guardCalls, "The zone must be guarded when the app is not already in it")
            assertEquals(listOf("login"), store.paths())
        }

    @Test
    fun leavingTheZoneAndComingBackAsksTheGuardAgain() = withStore { store ->
        store.navigation { navigateTo("home") }
        assertEquals(1, guardCalls)

        store.navigation {
            clearBackStack()
            navigateTo("login")
        }
        guardResult = { GuardResult.RedirectTo("splash") }
        store.navigation { navigateTo("home") }

        assertEquals(2, guardCalls, "Re-entering from outside the zone must be guarded")
        assertFalse(store.paths().any { it.startsWith("home") }, "The refused zone must not be entered")
    }

    @Test
    fun aDeepLinkIntoTheZoneTheAppIsAlreadyInStillMakesItsOwnCase() = withStore { store ->
        store.navigation { navigateTo("home") }
        assertEquals(1, guardCalls)

        store.navigateDeepLink("app/workspace")

        assertEquals(2, guardCalls, "A request from outside the app inherits no guard skip")
        assertEquals("home/workspace/workspace-overview", store.paths().last())
    }

    @Test
    fun aDeepLinkIntoTheZoneTheAppIsNotInIsGuarded() =
        withStore(initialGuardResult = { GuardResult.RedirectTo("login") }) { store ->
            store.navigateDeepLink("app/workspace")

            assertEquals(1, guardCalls)
            assertEquals("login", store.paths().last())
            assertFalse(store.paths().any { it.endsWith("workspace-overview") })
        }

    @Test
    fun aDynamicRootStartInsideTheZoneIsGuardedAndItsPlaceholderEarnsNothing() =
        withStore(RootStart.DynamicIntoZone, { GuardResult.RedirectTo("login") }) { store ->
            assertEquals(1, guardCalls, "Bootstrap into the zone must be guarded")
            assertEquals(listOf("login"), store.paths())

            store.navigateDeepLink("app/workspace")

            assertEquals(2, guardCalls)
            assertEquals("login", store.paths().last())
            assertFalse(store.paths().any { it.endsWith("workspace-overview") })
        }

    @Test
    fun aStaticRootStartInsideTheZoneDoesNotLetANavigationInheritAGuardNobodyPassed() =
        withStore(RootStart.StaticIntoZone, { GuardResult.RedirectTo("login") }) { store ->
            assertTrue(
                store.paths().all { it.startsWith("home") },
                "A static root start lands on its placeholder inside the zone"
            )
            val callsAtBootstrap = guardCalls

            store.navigation { navigateTo("home/workspace") }

            assertEquals(
                callsAtBootstrap + 1,
                guardCalls,
                "The placeholder is not a zone the app passed a guard to reach"
            )
            assertEquals("login", store.paths().last())
            assertFalse(store.paths().any { it.endsWith("workspace-overview") })
        }
}
