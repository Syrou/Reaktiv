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
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.DurationUnit
import kotlin.time.toDuration

@OptIn(ExperimentalCoroutinesApi::class)
class StartDslTest {

    private fun screen(route: String) = object : Screen {
        override val route = route
        override val enterTransition = NavTransition.None
        override val exitTransition = NavTransition.None

        @Composable
        override fun Content(params: Params) { Text(route) }
    }

    private fun loadingModal() = object : LoadingModal {
        override val route = "loading"
        override val enterTransition = NavTransition.None
        override val exitTransition = NavTransition.None

        @Composable
        override fun Content(params: Params) { Text("loading") }
    }

    private val homeScreen = screen("home")
    private val loginScreen = screen("login")
    private val dashboardScreen = screen("dashboard")
    private val detailScreen = screen("detail")

    @Test
    fun `start screen sets static initial screen`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val store = createStore {
                module(createNavigationModule {
                    rootGraph {
                        start(homeScreen)
                        screens(homeScreen)
                    }
                })
                coroutineContext(dispatcher)
            }
            advanceUntilIdle()

            val state = store.selectState<NavigationState>().first()
            assertEquals("home", state.currentEntry.route)
        }

    @Test
    fun `start screen auto-registers screen in navigatables`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val store = createStore {
                module(createNavigationModule {
                    rootGraph {
                        start(homeScreen)
                    }
                })
                coroutineContext(dispatcher)
            }
            advanceUntilIdle()

            store.navigation { navigateTo("home") }
            advanceUntilIdle()

            assertEquals("home", store.selectState<NavigationState>().first().currentEntry.route)
        }

    @Test
    fun `start graphId where target has static entry resolves to target start screen`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val store = createStore {
                module(createNavigationModule {
                    rootGraph {
                        start("workspace")
                        screens(homeScreen)
                        graph("workspace") {
                            start(dashboardScreen)
                            screens(dashboardScreen)
                        }
                    }
                })
                coroutineContext(dispatcher)
            }
            advanceUntilIdle()

            val state = store.selectState<NavigationState>().first()
            assertEquals("dashboard", state.currentEntry.route)
        }

    @Test
    fun `start graphId chain — graph references another graph with static entry`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val store = createStore {
                module(createNavigationModule {
                    rootGraph {
                        start("level1")
                        screens(homeScreen)
                        graph("level1") {
                            start("level2")
                            graph("level2") {
                                start(dashboardScreen)
                                screens(dashboardScreen)
                            }
                        }
                    }
                })
                coroutineContext(dispatcher)
            }
            advanceUntilIdle()

            val state = store.selectState<NavigationState>().first()
            assertEquals("dashboard", state.currentEntry.route)
            assertEquals("level2", state.currentEntry.graphId)
        }

    @Test
    fun `start graphId where target has dynamic entry — bootstrap resolves to correct screen`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val store = createStore {
                module(createNavigationModule {
                    loadingModal(loadingModal())
                    rootGraph {
                        start("workspace")
                        screens(homeScreen)
                        graph("workspace") {
                            start(route = { _ -> dashboardScreen })
                            screens(dashboardScreen)
                        }
                    }
                })
                coroutineContext(dispatcher)
            }
            advanceUntilIdle()

            val state = store.selectState<NavigationState>().first()
            assertEquals("dashboard", state.currentEntry.route)
            assertFalse(state.backStack.any { it.route == "loading" })
        }

    @Test
    fun `start graphId where target has dynamic entry — loadingModal shown while bootstrap runs`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val gate = kotlinx.coroutines.CompletableDeferred<Screen>()

            val store = createStore {
                module(createNavigationModule {
                    loadingModal(loadingModal())
                    rootGraph {
                        start("workspace")
                        screens(homeScreen)
                        graph("workspace") {
                            start(route = { gate.await() })
                            screens(dashboardScreen)
                        }
                    }
                })
                coroutineContext(dispatcher)
            }

            val initialState = store.selectState<NavigationState>().first()
            assertEquals("loading", initialState.currentEntry.route)

            gate.complete(dashboardScreen)
            advanceUntilIdle()

            val finalState = store.selectState<NavigationState>().first()
            assertEquals("dashboard", finalState.currentEntry.route)
            assertFalse(finalState.backStack.any { it.route == "loading" })
        }

    @Test
    fun `start graphId where target has dynamic entry and no loadingModal throws informative error`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val error = assertFailsWith<IllegalStateException> {
                createStore {
                    module(createNavigationModule {
                        rootGraph {
                            start("workspace")
                            screens(homeScreen)
                            graph("workspace") {
                                start(route = { _ -> dashboardScreen })
                                screens(dashboardScreen)
                            }
                        }
                    })
                    coroutineContext(dispatcher)
                }
            }
            val message = error.message ?: ""
            assertTrue(message.contains("dynamic start"), "Expected mention of dynamic start, got: $message")
            assertTrue(message.contains("loadingModal"), "Expected mention of loadingModal, got: $message")
        }

    @Test
    fun `start lambda on root requires loadingModal — throws informative error when absent`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val error = assertFailsWith<IllegalStateException> {
                createStore {
                    module(createNavigationModule {
                        rootGraph {
                            start(route = { _ -> homeScreen })
                            screens(homeScreen)
                        }
                    })
                    coroutineContext(dispatcher)
                }
            }
            val message = error.message ?: ""
            assertTrue(message.contains("dynamic"), "Expected mention of dynamic, got: $message")
            assertTrue(message.contains("loadingModal"), "Expected mention of loadingModal, got: $message")
        }

    @Test
    fun `start lambda on root with loadingModal — bootstrap resolves initial screen`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val store = createStore {
                module(createNavigationModule {
                    loadingModal(loadingModal())
                    rootGraph {
                        start(route = { _ -> homeScreen })
                        screens(homeScreen, loginScreen)
                    }
                })
                coroutineContext(dispatcher)
            }
            advanceUntilIdle()

            val state = store.selectState<NavigationState>().first()
            assertEquals("home", state.currentEntry.route)
            assertFalse(state.backStack.any { it.route == "loading" })
        }

    @Test
    fun `start lambda dynamic entry returning NavigationPath follows entry chain`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val store = createStore {
                module(createNavigationModule {
                    loadingModal(loadingModal())
                    rootGraph {
                        start(route = { _ -> NavigationPath("workspace") })
                        screens(homeScreen)
                        graph("workspace") {
                            start(dashboardScreen)
                            screens(dashboardScreen)
                        }
                    }
                })
                coroutineContext(dispatcher)
            }
            advanceUntilIdle()

            val state = store.selectState<NavigationState>().first()
            assertEquals("dashboard", state.currentEntry.route)
        }

    @Test
    fun `calling start twice on same graph throws error`() {
        assertFailsWith<IllegalStateException> {
            createNavigationModule {
                rootGraph {
                    start(homeScreen)
                    start(loginScreen)
                    screens(homeScreen, loginScreen)
                }
            }
        }
    }

    @Test
    fun `calling start screen then start lambda on same graph throws error`() {
        assertFailsWith<IllegalStateException> {
            createNavigationModule {
                rootGraph {
                    start(homeScreen)
                    start(route = { _ -> loginScreen })
                    screens(homeScreen, loginScreen)
                }
            }
        }
    }

    @Test
    fun `calling start lambda then start screen on same graph throws error`() {
        assertFailsWith<IllegalStateException> {
            createNavigationModule {
                rootGraph {
                    start(route = { _ -> homeScreen })
                    start(loginScreen)
                    screens(homeScreen, loginScreen)
                }
            }
        }
    }

    @Test
    fun `deprecated entry screen delegates to start and works correctly`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val dispatcher = StandardTestDispatcher(testScheduler)
            @Suppress("DEPRECATION")
            val store = createStore {
                module(createNavigationModule {
                    rootGraph {
                        entry(homeScreen)
                        screens(homeScreen)
                    }
                })
                coroutineContext(dispatcher)
            }
            advanceUntilIdle()

            assertEquals("home", store.selectState<NavigationState>().first().currentEntry.route)
        }

    @Test
    fun `deprecated startGraph delegates to start and works correctly`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val dispatcher = StandardTestDispatcher(testScheduler)
            @Suppress("DEPRECATION")
            val store = createStore {
                module(createNavigationModule {
                    rootGraph {
                        startGraph("workspace")
                        screens(homeScreen)
                        graph("workspace") {
                            start(dashboardScreen)
                            screens(dashboardScreen)
                        }
                    }
                })
                coroutineContext(dispatcher)
            }
            advanceUntilIdle()

            assertEquals("dashboard", store.selectState<NavigationState>().first().currentEntry.route)
        }

    @Test
    fun `deprecated entry lambda delegates to start and works correctly`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val dispatcher = StandardTestDispatcher(testScheduler)
            @Suppress("DEPRECATION")
            val store = createStore {
                module(createNavigationModule {
                    loadingModal(loadingModal())
                    rootGraph {
                        entry(route = { _ -> homeScreen })
                        screens(homeScreen)
                    }
                })
                coroutineContext(dispatcher)
            }
            advanceUntilIdle()

            assertEquals("home", store.selectState<NavigationState>().first().currentEntry.route)
        }

    @Test
    fun `root dynamic start is not re-invoked when resumePendingNavigation triggers synthesis`() =
        runTest(timeout = 10.toDuration(DurationUnit.SECONDS)) {
            var invokeCount = 0
            val dispatcher = StandardTestDispatcher(testScheduler)
            val store = createStore {
                module(createNavigationModule {
                    loadingModal(loadingModal())
                    rootGraph {
                        start(route = { _ ->
                            invokeCount++
                            homeScreen
                        })
                        screens(homeScreen, loginScreen, dashboardScreen)
                    }
                })
                coroutineContext(dispatcher)
            }
            advanceUntilIdle()
            assertEquals(1, invokeCount)

            store.navigation {
                clearBackStack()
                navigateTo("login")
            }
            advanceUntilIdle()

            store.navigation { resumePendingNavigation() }
            advanceUntilIdle()

            assertEquals(1, invokeCount, "Entry lambda must not be re-invoked during synthesis")
        }

    @Test
    fun `root dynamic start is not re-invoked when deep link triggers synthesis`() =
        runTest(timeout = 10.toDuration(DurationUnit.SECONDS)) {
            var invokeCount = 0
            val dispatcher = StandardTestDispatcher(testScheduler)
            val store = createStore {
                module(createNavigationModule {
                    loadingModal(loadingModal())
                    rootGraph {
                        start(route = { _ ->
                            invokeCount++
                            homeScreen
                        })
                        screens(homeScreen, dashboardScreen)
                        graph("sub") {
                            start(dashboardScreen)
                            screens(dashboardScreen)
                        }
                    }
                })
                coroutineContext(dispatcher)
            }
            advanceUntilIdle()
            assertEquals(1, invokeCount)

            store.navigateDeepLink("sub/dashboard")
            advanceUntilIdle()

            assertEquals(1, invokeCount, "Entry lambda must not be re-invoked during deep link synthesis")
            assertEquals("dashboard", store.selectState<NavigationState>().first().currentEntry.route)
        }

    @Test
    fun `nested graph dynamic start invoked only on first deep link not on second deep link to same graph`() =
        runTest(timeout = 10.toDuration(DurationUnit.SECONDS)) {
            var invokeCount = 0
            val dispatcher = StandardTestDispatcher(testScheduler)
            val store = createStore {
                module(createNavigationModule {
                    rootGraph {
                        start(homeScreen)
                        screens(homeScreen)
                        graph("workspace") {
                            start(route = { _ ->
                                invokeCount++
                                dashboardScreen
                            })
                            screens(dashboardScreen, detailScreen)
                        }
                    }
                })
                coroutineContext(dispatcher)
            }
            advanceUntilIdle()

            store.navigateDeepLink("workspace/dashboard")
            advanceUntilIdle()
            assertEquals(1, invokeCount)

            store.navigateDeepLink("workspace/detail")
            advanceUntilIdle()
            assertEquals(1, invokeCount, "Workspace entry lambda must not re-run on second deep link to workspace")
            assertEquals("detail", store.selectState<NavigationState>().first().currentEntry.route)
        }

    @Test
    fun `graph dynamic start is not re-invoked when already inside the graph`() =
        runTest(timeout = 10.toDuration(DurationUnit.SECONDS)) {
            var invokeCount = 0
            val dispatcher = StandardTestDispatcher(testScheduler)
            val store = createStore {
                module(createNavigationModule {
                    rootGraph {
                        start(homeScreen)
                        screens(homeScreen, loginScreen, dashboardScreen)
                        graph("workspace") {
                            start(route = { _ ->
                                invokeCount++
                                dashboardScreen
                            })
                            screens(dashboardScreen)
                        }
                    }
                })
                coroutineContext(dispatcher)
            }
            advanceUntilIdle()

            store.navigation { navigateTo("workspace") }
            advanceUntilIdle()
            assertEquals(1, invokeCount)
            assertEquals("dashboard", store.selectState<NavigationState>().first().currentEntry.route)

            store.navigation { navigateTo("workspace") }
            advanceUntilIdle()
            assertEquals(1, invokeCount, "Entry lambda must not re-run when already inside the graph")
        }

    @Test
    fun `intercept guard runs once on first entry then skipped for cross-graph navigation within zone`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            var guardCount = 0
            val dispatcher = StandardTestDispatcher(testScheduler)
            val store = createStore {
                module(createNavigationModule {
                    rootGraph {
                        start(homeScreen)
                        screens(homeScreen)
                        intercept(guard = { _ ->
                            guardCount++
                            GuardResult.Allow
                        }) {
                            graph("workspace") {
                                start(dashboardScreen)
                                screens(dashboardScreen)
                            }
                            graph("detail") {
                                start(detailScreen)
                                screens(detailScreen)
                            }
                        }
                    }
                })
                coroutineContext(dispatcher)
            }
            advanceUntilIdle()

            store.navigation { navigateTo("workspace") }
            advanceUntilIdle()
            assertEquals(1, guardCount, "Guard should run on first entry into protected zone")

            store.navigation { navigateTo("detail") }
            advanceUntilIdle()
            assertEquals(1, guardCount, "Guard must not re-run when already inside the protected zone")
        }

    @Test
    fun `intercept guard skipped when deep linking within already-entered zone`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            var guardCount = 0
            val dispatcher = StandardTestDispatcher(testScheduler)
            val store = createStore {
                module(createNavigationModule {
                    rootGraph {
                        start(homeScreen)
                        screens(homeScreen)
                        intercept(guard = { _ ->
                            guardCount++
                            GuardResult.Allow
                        }) {
                            graph("workspace") {
                                start(dashboardScreen)
                                screens(dashboardScreen, detailScreen)
                            }
                        }
                    }
                })
                coroutineContext(dispatcher)
            }
            advanceUntilIdle()

            store.navigation { navigateTo("workspace") }
            advanceUntilIdle()
            assertEquals(1, guardCount)

            store.navigateDeepLink("workspace/detail")
            advanceUntilIdle()
            assertEquals(1, guardCount, "Guard must not re-run on deep link within already-entered zone")
            assertEquals("detail", store.selectState<NavigationState>().first().currentEntry.route)
        }

    @Test
    fun `intercept guard runs again after leaving and re-entering the protected zone`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            var guardCount = 0
            val dispatcher = StandardTestDispatcher(testScheduler)
            val store = createStore {
                module(createNavigationModule {
                    rootGraph {
                        start(homeScreen)
                        screens(homeScreen)
                        intercept(guard = { _ ->
                            guardCount++
                            GuardResult.Allow
                        }) {
                            graph("workspace") {
                                start(dashboardScreen)
                                screens(dashboardScreen)
                            }
                        }
                    }
                })
                coroutineContext(dispatcher)
            }
            advanceUntilIdle()

            store.navigation { navigateTo("workspace") }
            advanceUntilIdle()
            assertEquals(1, guardCount)

            store.navigation {
                clearBackStack()
                navigateTo("home")
            }
            advanceUntilIdle()

            store.navigation { navigateTo("workspace") }
            advanceUntilIdle()
            assertEquals(2, guardCount, "Guard must run again after re-entering from outside the zone")
        }

    @Test
    fun `params passed to graph route are forwarded to the resolved start screen`() =
        runTest(timeout = 10.toDuration(DurationUnit.SECONDS)) {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val store = createStore {
                module(createNavigationModule {
                    loadingModal(loadingModal())
                    rootGraph {
                        start(homeScreen)
                        screens(homeScreen)
                        graph("workspace") {
                            start(route = { _ ->
                                dashboardScreen
                            })
                            screens(dashboardScreen, detailScreen)
                        }
                    }
                })
                coroutineContext(dispatcher)
            }
            advanceUntilIdle()

            store.navigation {
                navigateTo("workspace") {
                    put("userId", "abc123")
                    put("tab", "overview")
                }
            }
            advanceUntilIdle()

            val state = store.selectState<NavigationState>().first()
            assertEquals("dashboard", state.currentEntry.route)
            assertEquals("abc123", state.currentEntry.params.getTyped<String>("userId"))
            assertEquals("overview", state.currentEntry.params.getTyped<String>("tab"))
        }
}
