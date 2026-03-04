import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import io.github.syrou.reaktiv.core.createStore
import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.createNavigationModule
import io.github.syrou.reaktiv.navigation.definition.LoadingModal
import io.github.syrou.reaktiv.navigation.definition.NavigationPath
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.extension.navigateDeepLink
import io.github.syrou.reaktiv.navigation.param.Params
import io.github.syrou.reaktiv.navigation.transition.NavTransition
import io.github.syrou.reaktiv.core.util.selectState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.DurationUnit
import kotlin.time.toDuration

/**
 * Tests for deep link backstack synthesis behaviour:
 *
 * - The root graph entry is always anchored at the bottom of the synthesized backstack.
 * - Dynamic `entry { route = { ... } }` lambdas are evaluated during synthesis for both
 *   the root graph and intermediate nested graphs.
 * - Cross-graph deep links get the root entry prepended before the target path hierarchy.
 * - Entries already present in the backstack are never duplicated.
 *
 * A root graph with only a dynamic entry (no static startScreen) requires a `loadingModal`
 * at the module level so that the module can produce an initial state before the dynamic
 * lambda is first evaluated.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DeepLinkSynthesisTest {

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

    private val splashScreen        = screen("splash")
    private val loginScreen         = screen("login")
    private val workspaceHome       = screen("home")
    private val workspaceDetail     = screen("detail")
    private val settingsHome        = screen("settings-home")
    private val notificationsScreen = screen("notifications")

    @Test
    fun `static root entry - same-graph deep link anchors root entry at bottom`() =
        runTest(timeout = 10.toDuration(DurationUnit.SECONDS)) {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val store = createStore {
                module(createNavigationModule {
                    rootGraph {
                        entry(splashScreen)
                        screens(splashScreen, loginScreen)
                        graph("workspace") {
                            entry(workspaceHome)
                            screens(workspaceHome, workspaceDetail)
                        }
                    }
                })
                coroutineContext(dispatcher)
            }
            advanceUntilIdle()

            store.navigateDeepLink("workspace/detail")
            advanceUntilIdle()

            val state = store.selectState<NavigationState>().first()
            assertEquals("detail", state.currentEntry.route)
            assertEquals(3, state.backStack.size)
            assertEquals("splash", state.backStack[0].route)
            assertEquals("home",   state.backStack[1].route)
            assertEquals("detail", state.backStack[2].route)
        }

    @Test
    fun `static root entry - cross-graph deep link anchors root entry before target hierarchy`() =
        runTest(timeout = 10.toDuration(DurationUnit.SECONDS)) {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val store = createStore {
                module(createNavigationModule {
                    rootGraph {
                        entry(splashScreen)
                        screens(splashScreen, loginScreen)
                        graph("workspace") {
                            entry(workspaceHome)
                            screens(workspaceHome, workspaceDetail)
                        }
                        graph("settings") {
                            entry(settingsHome)
                            screens(settingsHome, notificationsScreen)
                        }
                    }
                })
                coroutineContext(dispatcher)
            }
            advanceUntilIdle()

            store.navigateDeepLink("settings/notifications")
            advanceUntilIdle()

            val state = store.selectState<NavigationState>().first()
            assertEquals("notifications", state.currentEntry.route)
            assertEquals(3, state.backStack.size)
            assertEquals("splash",        state.backStack[0].route)
            assertEquals("settings-home", state.backStack[1].route)
            assertEquals("notifications", state.backStack[2].route)
        }

    @Test
    fun `deep link to root-level screen does not duplicate root entry`() =
        runTest(timeout = 10.toDuration(DurationUnit.SECONDS)) {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val store = createStore {
                module(createNavigationModule {
                    rootGraph {
                        entry(splashScreen)
                        screens(splashScreen, loginScreen)
                        graph("workspace") {
                            entry(workspaceHome)
                            screens(workspaceHome, workspaceDetail)
                        }
                    }
                })
                coroutineContext(dispatcher)
            }
            advanceUntilIdle()

            store.navigateDeepLink("login")
            advanceUntilIdle()

            val state = store.selectState<NavigationState>().first()
            assertEquals("login", state.currentEntry.route)
            val splashCount = state.backStack.count { it.route == "splash" }
            assertEquals(1, splashCount, "Root entry must not be duplicated in the backstack")
        }

    @Test
    fun `dynamic root entry - same-graph deep link evaluates lambda and anchors result at bottom`() =
        runTest(timeout = 10.toDuration(DurationUnit.SECONDS)) {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val store = createStore {
                module(createNavigationModule {
                    loadingModal(loadingScreen)
                    rootGraph {
                        entry(route = { _ -> splashScreen })
                        screens(splashScreen, loginScreen)
                        graph("workspace") {
                            entry(workspaceHome)
                            screens(workspaceHome, workspaceDetail)
                        }
                    }
                })
                coroutineContext(dispatcher)
            }
            advanceUntilIdle()

            store.navigateDeepLink("workspace/detail")
            advanceUntilIdle()

            val state = store.selectState<NavigationState>().first()
            assertEquals("detail", state.currentEntry.route)
            assertEquals(3, state.backStack.size)
            assertEquals("splash", state.backStack[0].route)
            assertEquals("home",   state.backStack[1].route)
            assertEquals("detail", state.backStack[2].route)
        }

    @Test
    fun `dynamic root entry returning NavigationPath - deep link resolves and anchors`() =
        runTest(timeout = 10.toDuration(DurationUnit.SECONDS)) {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val store = createStore {
                module(createNavigationModule {
                    loadingModal(loadingScreen)
                    rootGraph {
                        entry(route = { _ -> NavigationPath("login") })
                        screens(splashScreen, loginScreen)
                        graph("workspace") {
                            entry(workspaceHome)
                            screens(workspaceHome, workspaceDetail)
                        }
                    }
                })
                coroutineContext(dispatcher)
            }
            advanceUntilIdle()

            store.navigateDeepLink("workspace/detail")
            advanceUntilIdle()

            val state = store.selectState<NavigationState>().first()
            assertEquals("detail", state.currentEntry.route)
            assertEquals(3, state.backStack.size)
            assertEquals("login",  state.backStack[0].route)
            assertEquals("home",   state.backStack[1].route)
            assertEquals("detail", state.backStack[2].route)
        }

    @Test
    fun `dynamic root entry - cross-graph deep link evaluates lambda and places it at bottom`() =
        runTest(timeout = 10.toDuration(DurationUnit.SECONDS)) {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val store = createStore {
                module(createNavigationModule {
                    loadingModal(loadingScreen)
                    rootGraph {
                        entry(route = { _ -> splashScreen })
                        screens(splashScreen, loginScreen)
                        graph("workspace") {
                            entry(workspaceHome)
                            screens(workspaceHome, workspaceDetail)
                        }
                        graph("settings") {
                            entry(settingsHome)
                            screens(settingsHome, notificationsScreen)
                        }
                    }
                })
                coroutineContext(dispatcher)
            }
            advanceUntilIdle()

            store.navigateDeepLink("settings/notifications")
            advanceUntilIdle()

            val state = store.selectState<NavigationState>().first()
            assertEquals("notifications", state.currentEntry.route)
            assertEquals(3, state.backStack.size)
            assertEquals("splash",        state.backStack[0].route)
            assertEquals("settings-home", state.backStack[1].route)
            assertEquals("notifications", state.backStack[2].route)
        }

    @Test
    fun `dynamic workspace entry - deep link evaluates workspace lambda for intermediate entry`() =
        runTest(timeout = 10.toDuration(DurationUnit.SECONDS)) {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val store = createStore {
                module(createNavigationModule {
                    rootGraph {
                        entry(splashScreen)
                        screens(splashScreen, loginScreen)
                        graph("workspace") {
                            entry(route = { _ -> workspaceHome })
                            screens(workspaceHome, workspaceDetail)
                        }
                    }
                })
                coroutineContext(dispatcher)
            }
            advanceUntilIdle()

            store.navigateDeepLink("workspace/detail")
            advanceUntilIdle()

            val state = store.selectState<NavigationState>().first()
            assertEquals("detail", state.currentEntry.route)
            assertEquals(3, state.backStack.size)
            assertEquals("splash", state.backStack[0].route)
            assertEquals("home",   state.backStack[1].route)
            assertEquals("detail", state.backStack[2].route)
        }
}
