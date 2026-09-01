import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import io.github.syrou.reaktiv.core.Store
import io.github.syrou.reaktiv.core.createStore
import io.github.syrou.reaktiv.core.util.selectState
import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.createNavigationModule
import io.github.syrou.reaktiv.navigation.definition.LoadingModal
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
import kotlin.time.DurationUnit
import kotlin.time.toDuration

/**
 * A guard redirect replaces the navigation it intercepted, so it lands the way that navigation
 * would have: clearing when it cleared, with ancestors when it synthesized them, and never with
 * the guarded zone's own start beneath it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GuardRedirectStackTest {

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
    private val noSubscription = screen("insight-no-subscription")
    private val hubMain = screen("hub-main")

    private var insightGuard: () -> GuardResult = { GuardResult.Allow }
    private var insightGuardCalls = 0

    private fun module() = createNavigationModule {
        loadingModal(loadingScreen)
        rootGraph {
            start(splash)
            screens(splash, login)
            intercept(guard = { _ -> GuardResult.Allow }) {
                graph("home") {
                    start(homeStart)
                    screens(homeStart)
                    intercept(guard = { _ ->
                        insightGuardCalls++
                        insightGuard()
                    }) {
                        graph("insight") {
                            start("insight-hub")
                            screens(noSubscription)
                            graph("insight-hub") {
                                start(hubMain)
                                screens(hubMain)
                            }
                        }
                    }
                }
            }
        }
        deepLinkAliases {
            alias("studio/streams", "home/insight/insight-hub") { Params.empty() }
        }
    }

    private fun withStore(block: suspend (Store) -> Unit) =
        runTest(timeout = 20.toDuration(DurationUnit.SECONDS)) {
            insightGuardCalls = 0
            insightGuard = { GuardResult.Allow }
            val dispatcher = StandardTestDispatcher(testScheduler)
            val store = createStore {
                module(module())
                coroutineContext(dispatcher)
            }
            advanceUntilIdle()
            block(store)
            advanceUntilIdle()
        }

    private suspend fun Store.paths(): List<String> =
        selectState<NavigationState>().first().backStack.map { it.path }

    @Test
    fun deepLinkRedirectInsideTheZoneLandsAboveTheZoneNotOnItsStart() = withStore { store ->
        insightGuard = { GuardResult.RedirectTo("home/insight/insight-no-subscription") }

        store.navigateDeepLink("studio/streams")

        assertEquals(
            listOf("splash", "home/home-start", "home/insight/insight-no-subscription"),
            store.paths()
        )
        assertFalse(
            store.paths().any { it.endsWith("hub-main") },
            "The start the guard refused must not be synthesized beneath the redirect"
        )
        assertEquals(1, insightGuardCalls)
    }

    @Test
    fun deepLinkRedirectOutsideTheZoneClearsLikeTheDeepLinkWouldHave() = withStore { store ->
        store.navigation { navigateTo("home") }
        assertEquals(listOf("splash", "home/home-start"), store.paths())

        insightGuard = { GuardResult.RedirectTo("login") }
        store.navigateDeepLink("studio/streams")

        assertEquals(listOf("splash", "login"), store.paths())
    }

    @Test
    fun inAppRedirectKeepsTheStackItWasIssuedFrom() = withStore { store ->
        store.navigation { navigateTo("home") }

        insightGuard = { GuardResult.RedirectTo("login") }
        store.navigation { navigateTo("home/insight/insight-hub") }

        assertEquals(listOf("splash", "home/home-start", "login"), store.paths())
    }

    @Test
    fun deepLinkPendAndRedirectClearsAndRemembersTheTarget() = withStore { store ->
        store.navigation { navigateTo("home") }

        insightGuard = { GuardResult.PendAndRedirectTo("login") }
        store.navigateDeepLink("studio/streams")

        val state = store.selectState<NavigationState>().first()
        assertEquals(listOf("splash", "login"), state.backStack.map { it.path })
        assertEquals("home/insight/insight-hub", state.pendingNavigation?.route)
    }
}
