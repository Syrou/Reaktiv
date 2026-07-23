import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import kotlin.test.assertNull
import kotlin.time.DurationUnit
import kotlin.time.toDuration

@OptIn(ExperimentalCoroutinesApi::class)
class NestedDynamicGraphPathTest {

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

    private val splashScreen = screen("splash")
    private val loginScreen  = screen("login")
    private val homeStart    = screen("home-start")
    private val hubMain      = screen("hub-main")

    private fun testModule(
        guardResult: () -> GuardResult,
        onInsightEntry: () -> Unit = {}
    ) = createNavigationModule {
        loadingModal(loadingScreen)
        rootGraph {
            start(splashScreen)
            screens(splashScreen, loginScreen)
            intercept(guard = { _ -> guardResult() }) {
                graph("home") {
                    start(route = { _ -> homeStart })
                    screens(homeStart)
                    graph("insight") {
                        start(route = { _ ->
                            onInsightEntry()
                            NavigationPath("insight-hub")
                        })
                        graph("insight-hub") {
                            start(hubMain)
                            screens(hubMain)
                        }
                    }
                }
            }
        }
        deepLinkAliases {
            alias("/studio/streams", "home/insight") { Params.empty() }
        }
    }

    @Test
    fun `deep link with leading slash to nested dynamic graph evaluates entry and synthesizes ancestors`() =
        runTest(timeout = 10.toDuration(DurationUnit.SECONDS)) {
            var entryCount = 0
            val dispatcher = StandardTestDispatcher(testScheduler)
            val store = createStore {
                module(testModule(guardResult = { GuardResult.Allow }, onInsightEntry = { entryCount++ }))
                coroutineContext(dispatcher)
            }
            advanceUntilIdle()

            store.navigateDeepLink("/home/insight")
            advanceUntilIdle()

            val state = store.selectState<NavigationState>().first()
            assertEquals("hub-main", state.currentEntry.route)
            assertEquals(1, entryCount, "Insight entry lambda must be evaluated exactly once")
            assertEquals(3, state.backStack.size)
            assertEquals("splash",     state.backStack[0].route)
            assertEquals("home-start", state.backStack[1].route)
            assertEquals("hub-main",   state.backStack[2].route)
        }

    @Test
    fun `in-app navigateTo full path of nested dynamic graph resolves entry`() =
        runTest(timeout = 10.toDuration(DurationUnit.SECONDS)) {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val store = createStore {
                module(testModule(guardResult = { GuardResult.Allow }))
                coroutineContext(dispatcher)
            }
            advanceUntilIdle()

            store.navigation { navigateTo("home/insight") }
            advanceUntilIdle()

            val state = store.selectState<NavigationState>().first()
            assertEquals("hub-main", state.currentEntry.route)
        }

    @Test
    fun `in-app navigateTo bare graph id of nested dynamic graph still resolves entry`() =
        runTest(timeout = 10.toDuration(DurationUnit.SECONDS)) {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val store = createStore {
                module(testModule(guardResult = { GuardResult.Allow }))
                coroutineContext(dispatcher)
            }
            advanceUntilIdle()

            store.navigation { navigateTo("insight") }
            advanceUntilIdle()

            val state = store.selectState<NavigationState>().first()
            assertEquals("hub-main", state.currentEntry.route)
        }

    @Test
    fun `deep link alias targeting nested dynamic graph full path resolves entry`() =
        runTest(timeout = 10.toDuration(DurationUnit.SECONDS)) {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val store = createStore {
                module(testModule(guardResult = { GuardResult.Allow }))
                coroutineContext(dispatcher)
            }
            advanceUntilIdle()

            store.navigateDeepLink("/studio/streams")
            advanceUntilIdle()

            val state = store.selectState<NavigationState>().first()
            assertEquals("hub-main", state.currentEntry.route)
            assertEquals(3, state.backStack.size)
            assertEquals("splash",     state.backStack[0].route)
            assertEquals("home-start", state.backStack[1].route)
            assertEquals("hub-main",   state.backStack[2].route)
        }

    @Test
    fun `guard rejects deep link to nested dynamic graph before entry lambda runs`() =
        runTest(timeout = 10.toDuration(DurationUnit.SECONDS)) {
            var entryCount = 0
            val dispatcher = StandardTestDispatcher(testScheduler)
            val store = createStore {
                module(testModule(
                    guardResult = { GuardResult.RedirectTo("login") },
                    onInsightEntry = { entryCount++ }
                ))
                coroutineContext(dispatcher)
            }
            advanceUntilIdle()

            store.navigateDeepLink("/home/insight")
            advanceUntilIdle()

            val state = store.selectState<NavigationState>().first()
            assertEquals("login", state.currentEntry.route)
            assertEquals(0, entryCount, "Guard must run before the entry lambda is evaluated")
        }

    @Test
    fun `pended deep link to nested dynamic graph resumes after guard passes`() =
        runTest(timeout = 10.toDuration(DurationUnit.SECONDS)) {
            var authenticated = false
            var entryCount = 0
            val dispatcher = StandardTestDispatcher(testScheduler)
            val store = createStore {
                module(testModule(
                    guardResult = {
                        if (authenticated) GuardResult.Allow else GuardResult.PendAndRedirectTo("login")
                    },
                    onInsightEntry = { entryCount++ }
                ))
                coroutineContext(dispatcher)
            }
            advanceUntilIdle()

            store.navigateDeepLink("/home/insight")
            advanceUntilIdle()

            assertEquals("login", store.selectState<NavigationState>().first().currentEntry.route)
            assertEquals(0, entryCount, "Entry lambda must not run while the navigation is pended")

            authenticated = true
            store.navigation {
                clearBackStack()
                resumePendingNavigation()
            }
            advanceUntilIdle()

            val state = store.selectState<NavigationState>().first()
            assertEquals("hub-main", state.currentEntry.route)
            assertEquals(1, entryCount, "Entry lambda must run exactly once on resume")
            assertNull(state.pendingNavigation)
            assertEquals(3, state.backStack.size)
            assertEquals("splash",     state.backStack[0].route)
            assertEquals("home-start", state.backStack[1].route)
            assertEquals("hub-main",   state.backStack[2].route)
        }

    @Test
    fun `query params on non-alias deep link reach the resolved entry`() =
        runTest(timeout = 10.toDuration(DurationUnit.SECONDS)) {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val store = createStore {
                module(testModule(guardResult = { GuardResult.Allow }))
                coroutineContext(dispatcher)
            }
            advanceUntilIdle()

            store.navigateDeepLink("/home/insight?source=push")
            advanceUntilIdle()

            val state = store.selectState<NavigationState>().first()
            assertEquals("hub-main", state.currentEntry.route)
            assertEquals("push", state.currentEntry.params.getString("source"))
        }
}
