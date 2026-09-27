import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import io.github.syrou.reaktiv.core.Module
import io.github.syrou.reaktiv.core.ModuleAction
import io.github.syrou.reaktiv.core.ModuleLogic
import io.github.syrou.reaktiv.core.ModuleState
import io.github.syrou.reaktiv.core.Store
import io.github.syrou.reaktiv.core.StoreAccessor
import io.github.syrou.reaktiv.core.createStore
import io.github.syrou.reaktiv.core.util.selectState
import io.github.syrou.reaktiv.navigation.NavigationLogic
import io.github.syrou.reaktiv.navigation.NavigationModule
import io.github.syrou.reaktiv.navigation.NavigationOutcome
import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.createNavigationModule
import io.github.syrou.reaktiv.navigation.definition.LoadingModal
import io.github.syrou.reaktiv.navigation.definition.Modal
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.dsl.NavigationBuilder
import io.github.syrou.reaktiv.navigation.extension.navigateDeepLink
import io.github.syrou.reaktiv.navigation.extension.navigation
import io.github.syrou.reaktiv.navigation.layer.RenderLayer
import io.github.syrou.reaktiv.navigation.model.GuardResult
import io.github.syrou.reaktiv.navigation.param.Params
import io.github.syrou.reaktiv.navigation.transition.NavTransition
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.DurationUnit
import kotlin.time.toDuration

@OptIn(ExperimentalCoroutinesApi::class)
class GuardCorrectnessTest {

    private fun screen(route: String) = object : Screen {
        override val route = route
        override val enterTransition = NavTransition.None
        override val exitTransition = NavTransition.None

        @Composable
        override fun Content(params: Params) {
            Text(route)
        }
    }

    private fun systemModal(route: String) = object : Modal {
        override val route = route
        override val enterTransition = NavTransition.None
        override val exitTransition = NavTransition.None
        override val renderLayer = RenderLayer.SYSTEM

        @Composable
        override fun Content(params: Params) {
            Text(route)
        }
    }

    private val loading = object : LoadingModal {
        override val route = "loading"
        override val enterTransition = NavTransition.None
        override val exitTransition = NavTransition.None

        @Composable
        override fun Content(params: Params) {
            Text(route)
        }
    }

    @Serializable
    data class AccessState(
        val loggedIn: Boolean = false,
        val admin: Boolean = false
    ) : ModuleState

    sealed class AccessAction : ModuleAction(AccessModule::class) {
        data object LogIn : AccessAction()
    }

    object AccessModule : Module<AccessState, AccessAction> {
        override val initialState = AccessState()
        override val reducer: (AccessState, AccessAction) -> AccessState = { state, action ->
            when (action) {
                AccessAction.LogIn -> state.copy(loggedIn = true)
            }
        }
        override val createLogic: (StoreAccessor) -> ModuleLogic = { object : ModuleLogic() {} }
    }

    private val requireLogin: suspend (StoreAccessor) -> GuardResult = { store ->
        if (store.selectState<AccessState>().value.loggedIn) GuardResult.Allow else GuardResult.Reject
    }

    private val requireAdmin: suspend (StoreAccessor) -> GuardResult = { store ->
        if (store.selectState<AccessState>().value.admin) GuardResult.Allow else GuardResult.Reject
    }

    private val home = screen("home")
    private val about = screen("about")
    private val wsHome = screen("overview")
    private val settings = screen("settings")
    private val vault = screen("vault")
    private val adminPanel = screen("panel")
    private val detail = screen("detail")

    private fun TestScope.storeWith(module: NavigationModule): Store = createStore {
        module(AccessModule)
        module(module)
        coroutineContext(StandardTestDispatcher(testScheduler))
    }

    private suspend fun Store.nav(): NavigationState = selectState<NavigationState>().first()

    private suspend fun Store.outcome(block: suspend NavigationBuilder.() -> Unit): NavigationOutcome =
        selectLogic<NavigationLogic>().navigate(block)

    @Test
    fun `a screen declared inside an inner intercept still passes the guard of the graph around it`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val store = storeWith(createNavigationModule {
                rootGraph {
                    start(home)
                    screens(home)
                    intercept(guard = requireLogin) {
                        graph("ws") {
                            start(wsHome)
                            screens(wsHome)
                            intercept(guard = { GuardResult.Allow }) {
                                screens(vault)
                            }
                        }
                    }
                }
            })
            advanceUntilIdle()

            val outcome = store.outcome { navigateTo("ws/vault") }
            advanceUntilIdle()

            assertEquals(NavigationOutcome.Rejected, outcome)
            assertEquals("home", store.nav().currentEntry.route)
        }

    @Test
    fun `navigating to a guarded graph whose start is in an inner zone passes the inner guard too`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val store = storeWith(createNavigationModule {
                rootGraph {
                    start(home)
                    screens(home)
                    intercept(guard = { GuardResult.Allow }) {
                        graph("ws") {
                            start("admin")
                            intercept(guard = requireAdmin) {
                                graph("admin") {
                                    start(adminPanel)
                                    screens(adminPanel)
                                }
                            }
                        }
                    }
                }
            })
            advanceUntilIdle()

            val outcome = store.outcome { navigateTo("ws") }
            advanceUntilIdle()

            assertEquals(NavigationOutcome.Rejected, outcome)
            assertEquals("home", store.nav().currentEntry.route)
        }

    @Test
    fun `every navigation in a block passes its own guard and not only the first`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val store = storeWith(createNavigationModule {
                rootGraph {
                    start(home)
                    screens(home, about)
                    intercept(guard = requireLogin) {
                        graph("ws") {
                            start(wsHome)
                            screens(wsHome, settings)
                        }
                    }
                }
            })
            advanceUntilIdle()

            val outcome = store.outcome {
                navigateTo("about")
                navigateTo("ws/settings")
            }
            advanceUntilIdle()

            assertEquals(NavigationOutcome.Rejected, outcome)
            assertEquals(listOf("home"), store.nav().backStack.map { it.route })
        }

    @Test
    fun `a popUpTo fallback into a guarded zone passes that guard`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val store = storeWith(createNavigationModule {
                rootGraph {
                    start(home)
                    screens(home, about)
                    intercept(guard = requireLogin) {
                        graph("ws") {
                            start(wsHome)
                            screens(wsHome, settings)
                        }
                    }
                }
            })
            advanceUntilIdle()

            val outcome = store.outcome { popUpTo("about", fallback = "ws/settings") }
            advanceUntilIdle()

            assertEquals(NavigationOutcome.Rejected, outcome)
            assertEquals("home", store.nav().currentEntry.route)
        }

    @Test
    fun `a public deep link never places a guarded start beneath it`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val store = storeWith(createNavigationModule {
                notFoundScreen(screen("not-found"))
                rootGraph {
                    start("ws")
                    screens(about)
                    intercept(guard = requireLogin) {
                        graph("ws") {
                            start(wsHome)
                            screens(wsHome, settings)
                        }
                    }
                }
            })
            advanceUntilIdle()

            store.navigateDeepLink("about")
            advanceUntilIdle()
            assertFalse(
                store.nav().backStack.any { it.path.startsWith("ws/") },
                "A guarded start must not be synthesized under a public link: ${store.nav().backStack.map { it.path }}"
            )

            val outcome = store.outcome { navigateTo("ws/settings") }
            advanceUntilIdle()
            assertEquals(NavigationOutcome.Rejected, outcome)
            assertEquals("about", store.nav().currentEntry.route)
        }

    @Test
    fun `a static root start inside a guarded zone passes the guard before the app shows it`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val store = storeWith(createNavigationModule {
                notFoundScreen(screen("not-found"))
                rootGraph {
                    start("ws")
                    screens(about)
                    intercept(guard = requireLogin) {
                        graph("ws") {
                            start(wsHome)
                            screens(wsHome)
                        }
                    }
                }
            })
            advanceUntilIdle()

            assertEquals(listOf("not-found"), store.nav().backStack.map { it.path })
        }

    @Test
    fun `a dynamic start that resolves into another zone passes that zone's guard`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val store = storeWith(createNavigationModule {
                rootGraph {
                    start(home)
                    screens(home)
                    intercept(guard = requireLogin) {
                        graph("ws") {
                            start(route = { _ -> adminPanel })
                            screens(wsHome)
                            intercept(guard = requireAdmin) {
                                graph("admin") {
                                    start(adminPanel)
                                    screens(adminPanel)
                                }
                            }
                        }
                    }
                }
            })
            advanceUntilIdle()
            store.dispatch(AccessAction.LogIn)
            advanceUntilIdle()

            val outcome = store.outcome { navigateTo("ws") }
            advanceUntilIdle()

            assertEquals(NavigationOutcome.Rejected, outcome)
            assertEquals("home", store.nav().currentEntry.route)
        }

    @Test
    fun `a screen registered in two graphs lands on the path that was asked for`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val store = storeWith(createNavigationModule {
                rootGraph {
                    start(home)
                    screens(home)
                    intercept(guard = requireLogin) {
                        graph("account") {
                            start(detail)
                            screens(detail)
                        }
                    }
                    intercept(guard = requireAdmin) {
                        graph("admin") {
                            start(adminPanel)
                            screens(adminPanel, detail)
                        }
                    }
                }
            })
            advanceUntilIdle()
            store.dispatch(AccessAction.LogIn)
            advanceUntilIdle()

            store.navigation { navigateTo("account/detail") }
            advanceUntilIdle()
            assertEquals("account/detail", store.nav().currentEntry.path)

            val outcome = store.outcome { navigateTo("admin/panel") }
            advanceUntilIdle()
            assertEquals(NavigationOutcome.Rejected, outcome)
            assertEquals("account/detail", store.nav().currentEntry.path)
        }

    @Test
    fun `a guard that navigates while the app is starting does not freeze navigation`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val onboarding = screen("onboarding")
            val store = storeWith(createNavigationModule {
                notFoundScreen(screen("not-found"))
                rootGraph {
                    start(route = { _ -> wsHome })
                    screens(about, onboarding)
                    intercept(guard = { store ->
                        store.navigation { navigateTo("onboarding") }
                        GuardResult.Reject
                    }) {
                        graph("ws") {
                            start(wsHome)
                            screens(wsHome)
                        }
                    }
                }
            })
            advanceUntilIdle()

            store.navigation { navigateTo("about") }
            advanceUntilIdle()

            assertEquals("about", store.nav().currentEntry.route)
        }

    @Test
    fun `a navigation that finishes during another one's slow guard leaves its loading overlay up`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val alert = systemModal("alert")
            val store = storeWith(createNavigationModule {
                loadingModal(loading)
                rootGraph {
                    start(home)
                    screens(home)
                    modals(alert)
                    intercept(
                        guard = {
                            delay(3_000)
                            GuardResult.Allow
                        },
                        loadingThreshold = 200.milliseconds
                    ) {
                        graph("ws") {
                            start(wsHome)
                            screens(wsHome)
                        }
                    }
                }
            })
            advanceUntilIdle()

            launch { store.navigation { navigateTo("ws") } }
            advanceTimeBy(500)
            assertTrue(store.nav().isEvaluatingNavigation, "the slow guard shows the overlay")

            store.navigation { navigateTo("alert") }
            advanceTimeBy(100)
            assertTrue(
                store.nav().isEvaluatingNavigation,
                "the alert's navigation must not take down an overlay it did not raise"
            )

            advanceUntilIdle()
            assertFalse(store.nav().isEvaluatingNavigation)
            assertEquals("overview", store.nav().backStack.last { it.navigatable is Screen }.route)
        }

    private fun TestScope.storeWithGuardedSystemModal() = storeWith(createNavigationModule {
        loadingModal(loading)
        rootGraph {
            start(home)
            screens(home)
            intercept(
                guard = {
                    delay(2_000)
                    GuardResult.Allow
                },
                loadingThreshold = 200.milliseconds
            ) {
                modals(systemModal("invite"))
            }
        }
    })

    @Test
    fun `a slow guard in front of a system modal shows the loading overlay`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val store = storeWithGuardedSystemModal()
            advanceUntilIdle()

            launch { store.navigation { navigateTo("invite") } }
            advanceTimeBy(500)
            assertTrue(store.nav().isEvaluatingNavigation, "the slow guard shows the overlay")

            advanceUntilIdle()
            assertFalse(store.nav().isEvaluatingNavigation)
            assertEquals("invite", store.nav().currentEntry.route)
        }

    @Test
    fun `a slow guard in front of a deep linked system modal shows the loading overlay`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val store = storeWithGuardedSystemModal()
            advanceUntilIdle()

            launch { store.navigateDeepLink("invite") }
            advanceTimeBy(500)
            assertTrue(store.nav().isEvaluatingNavigation, "the slow guard shows the overlay")

            advanceUntilIdle()
            assertFalse(store.nav().isEvaluatingNavigation)
            assertEquals("invite", store.nav().currentEntry.route)
        }
}
