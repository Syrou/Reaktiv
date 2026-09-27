import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import io.github.syrou.reaktiv.core.Middleware
import io.github.syrou.reaktiv.core.Store
import io.github.syrou.reaktiv.core.createStore
import io.github.syrou.reaktiv.core.util.selectState
import io.github.syrou.reaktiv.navigation.NavigationAction
import io.github.syrou.reaktiv.navigation.NavigationLogic
import io.github.syrou.reaktiv.navigation.NavigationModule
import io.github.syrou.reaktiv.navigation.NavigationOutcome
import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.createNavigationModule
import io.github.syrou.reaktiv.navigation.definition.BackstackLifecycle
import io.github.syrou.reaktiv.navigation.definition.Modal
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.extension.navigateBack
import io.github.syrou.reaktiv.navigation.extension.navigateDeepLink
import io.github.syrou.reaktiv.navigation.extension.navigation
import io.github.syrou.reaktiv.navigation.history.LocationCodec
import io.github.syrou.reaktiv.navigation.layer.RenderLayer
import io.github.syrou.reaktiv.navigation.link.LinkOutcome
import io.github.syrou.reaktiv.navigation.model.NavigationEntry
import io.github.syrou.reaktiv.navigation.param.Params
import io.github.syrou.reaktiv.navigation.transition.NavTransition
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.DurationUnit
import kotlin.time.toDuration

@OptIn(ExperimentalCoroutinesApi::class)
class NavigationStackCorrectnessTest {

    private fun screen(
        route: String,
        forwardMs: Int = 0,
        popMs: Int = 0,
        onCreated: (suspend (BackstackLifecycle) -> Unit)? = null
    ) = object : Screen {
        override val route = route
        override val enterTransition = if (forwardMs > 0) NavTransition.Custom(durationMillis = forwardMs) else NavTransition.None
        override val exitTransition = if (forwardMs > 0) NavTransition.Custom(durationMillis = forwardMs) else NavTransition.None
        override val popEnterTransition = if (popMs > 0) NavTransition.Custom(durationMillis = popMs) else null
        override val popExitTransition = if (popMs > 0) NavTransition.Custom(durationMillis = popMs) else null

        override suspend fun onLifecycleCreated(lifecycle: BackstackLifecycle) {
            onCreated?.invoke(lifecycle)
        }

        @Composable
        override fun Content(params: Params) {
            Text(route)
        }
    }

    private fun modal(route: String, layer: RenderLayer = RenderLayer.GLOBAL_OVERLAY) = object : Modal {
        override val route = route
        override val enterTransition = NavTransition.None
        override val exitTransition = NavTransition.None
        override val renderLayer = layer

        @Composable
        override fun Content(params: Params) {
            Text(route)
        }
    }

    private val home = screen("home")
    private val profile = screen("profile")
    private val settings = screen("settings")
    private val sheet = modal("sheet")
    private val alert = modal("alert", RenderLayer.SYSTEM)

    private fun TestScope.storeWith(module: NavigationModule, vararg middlewares: Middleware): Store = createStore {
        module(module)
        if (middlewares.isNotEmpty()) middlewares(*middlewares)
        coroutineContext(StandardTestDispatcher(testScheduler))
    }

    private suspend fun Store.nav(): NavigationState = selectState<NavigationState>().first()

    private suspend fun Store.routes(): List<String> = nav().backStack.map { it.route }

    private suspend fun Store.logic(): NavigationLogic = selectLogic<NavigationLogic>()

    private fun basicModule() = createNavigationModule {
        rootGraph {
            start(home)
            screens(home, profile, settings)
            modals(sheet, alert)
        }
    }

    @Test
    fun `clearing the back stack on its own keeps where the app is and drops the history`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val store = storeWith(basicModule())
            advanceUntilIdle()
            store.navigation { navigateTo("profile") }
            advanceUntilIdle()

            store.navigation { clearBackStack() }
            advanceUntilIdle()

            assertEquals(listOf("profile"), store.routes())
            assertEquals("profile", store.nav().currentEntry.route)
        }

    @Test
    fun `clearBackStack with a new route lands on only that route`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val store = storeWith(basicModule())
            advanceUntilIdle()
            store.navigation { navigateTo("profile") }
            advanceUntilIdle()

            store.logic().clearBackStack("settings")
            advanceUntilIdle()

            assertEquals(listOf("settings"), store.routes())
            assertEquals("settings", store.nav().currentEntry.route)
        }

    @Test
    fun `clearing then navigating settles for a forward transition as the screen animates`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val a = screen("a", forwardMs = 300, popMs = 50)
            val b = screen("b", forwardMs = 300, popMs = 50)
            val c = screen("c", forwardMs = 300, popMs = 50)
            val store = storeWith(createNavigationModule {
                rootGraph {
                    start(a)
                    screens(a, b, c)
                }
            })
            advanceUntilIdle()
            store.navigation { navigateTo("b") }
            advanceUntilIdle()

            val startedAt = testScheduler.currentTime
            store.navigation {
                clearBackStack()
                navigateTo("c")
            }

            assertTrue(
                testScheduler.currentTime - startedAt >= 300,
                "clear then navigate is a forward transition, so it settles on the 300ms push, not the 50ms pop"
            )
        }

    @Test
    fun `a hash style link opens the route after the hash`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val store = storeWith(basicModule())
            advanceUntilIdle()

            val outcome = store.logic().openLink("https://example.com/#/profile", emptyMap())
            advanceUntilIdle()

            assertEquals(LinkOutcome.Landed("profile"), outcome)
            assertEquals("profile", store.nav().currentEntry.route)
        }

    @Test
    fun `a link to the site root opens the start destination`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val store = storeWith(basicModule())
            advanceUntilIdle()
            store.navigation { navigateTo("profile") }
            advanceUntilIdle()

            val outcome = store.logic().openLink("https://example.com/", emptyMap())
            advanceUntilIdle()

            assertEquals(LinkOutcome.Landed("home"), outcome)
        }

    @Test
    fun `resolving a graph with no start reports nothing instead of the not found screen`() {
        val module = createNavigationModule {
            notFoundScreen(screen("not-found"))
            rootGraph {
                start(home)
                screens(home)
                graph("umbrella") {
                    screens(profile)
                }
            }
        }

        assertNull(module.precomputedData.routeResolver.resolve("umbrella"))
    }

    @Test
    fun `navigating to a graph with no start still lands on the not found screen`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val store = storeWith(createNavigationModule {
                notFoundScreen(screen("not-found"))
                rootGraph {
                    start(home)
                    screens(home)
                    graph("umbrella") {
                        screens(profile)
                    }
                }
            })
            advanceUntilIdle()

            store.navigation { navigateTo("umbrella") }
            advanceUntilIdle()

            assertEquals("not-found", store.nav().currentEntry.route)
        }

    @Test
    fun `a navigation that a middleware blocks is not reported as a success`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val blocker: Middleware = { action, _, _, updatedState ->
                val blocked = action is NavigationAction.Navigate && action.entry.route == "profile"
                if (!blocked) updatedState(action)
            }
            val store = storeWith(basicModule(), blocker)
            advanceUntilIdle()

            val outcome = store.logic().navigate { navigateTo("profile") }
            advanceUntilIdle()

            assertNotEquals(NavigationOutcome.Success, outcome)
            assertEquals("home", store.nav().currentEntry.route)
        }

    @Test
    fun `a slow lifecycle hook does not hold up the navigation that created it`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val slow = screen("slow", onCreated = { delay(10_000) })
            val store = storeWith(createNavigationModule {
                rootGraph {
                    start(home)
                    screens(home, slow, profile)
                }
            })
            advanceUntilIdle()

            val startedAt = testScheduler.currentTime
            store.navigation { navigateTo("slow") }
            store.navigation { navigateTo("profile") }

            assertTrue(testScheduler.currentTime - startedAt < 1_000, "took ${testScheduler.currentTime - startedAt}ms")
            assertEquals("profile", store.nav().currentEntry.route)
        }

    @Test
    fun `navigating to a dynamic graph entered elsewhere runs its start instead of reusing another screen`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val dashboard = screen("dashboard")
            val item = screen("item/{id}")
            val store = storeWith(createNavigationModule {
                rootGraph {
                    start(home)
                    screens(home)
                    graph("ws") {
                        start(route = { _ -> dashboard })
                        screens(dashboard, item)
                    }
                }
            })
            advanceUntilIdle()
            store.navigation { navigateTo("ws/item/7") }
            advanceUntilIdle()

            store.navigation { navigateTo("ws") }
            advanceUntilIdle()

            assertEquals("dashboard", store.nav().currentEntry.route)
        }

    @Test
    fun `a graph whose start points at a graph with a dynamic start resolves through both`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val innerHome = screen("inner-home")
            val store = storeWith(createNavigationModule {
                rootGraph {
                    start(home)
                    screens(home)
                    graph("outer") {
                        start("inner")
                        graph("inner") {
                            start(route = { _ -> innerHome })
                            screens(innerHome)
                        }
                    }
                }
            })
            advanceUntilIdle()

            store.navigation { navigateTo("outer") }
            advanceUntilIdle()

            assertEquals("inner-home", store.nav().currentEntry.route)
        }

    @Test
    fun `replacing the current screen keeps a system alert on top`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val store = storeWith(basicModule())
            advanceUntilIdle()
            store.navigation { navigateTo("settings") }
            store.navigation { navigateTo("alert") }
            advanceUntilIdle()

            store.navigation { navigateTo("profile", replaceCurrent = true) }
            advanceUntilIdle()

            assertEquals(listOf("home", "profile", "alert"), store.routes())
        }

    @Test
    fun `popping up to a screen keeps a system alert on top`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val store = storeWith(basicModule())
            advanceUntilIdle()
            store.navigation { navigateTo("profile") }
            store.navigation { navigateTo("settings") }
            store.navigation { navigateTo("alert") }
            advanceUntilIdle()

            store.navigation { popUpTo("profile") }
            advanceUntilIdle()

            assertEquals(listOf("home", "profile", "alert"), store.routes())
        }

    @Test
    fun `replacing into a graph with a dynamic start replaces instead of pushing`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val dashboard = screen("dashboard")
            val store = storeWith(createNavigationModule {
                rootGraph {
                    start(home)
                    screens(home, profile)
                    graph("ws") {
                        start(route = { _ -> dashboard })
                        screens(dashboard)
                    }
                }
            })
            advanceUntilIdle()
            store.navigation { navigateTo("profile") }
            advanceUntilIdle()

            store.navigation { navigateTo("ws", replaceCurrent = true) }
            advanceUntilIdle()

            assertEquals(listOf("home", "dashboard"), store.routes())
        }

    @Test
    fun `dismissing a covered modal removes only the modal`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val store = storeWith(basicModule())
            advanceUntilIdle()
            store.navigation { navigateTo("sheet") }
            store.navigation { navigateTo("profile") }
            store.navigation { navigateTo("settings") }
            advanceUntilIdle()
            assertEquals(listOf("home", "sheet", "profile", "settings"), store.routes())

            store.logic().dismissModal()
            advanceUntilIdle()

            assertEquals(listOf("home", "profile", "settings"), store.routes())
        }

    @Test
    fun `navigating to the screen under a modal while dismissing modals does not duplicate it`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val store = storeWith(basicModule())
            advanceUntilIdle()
            store.navigation { navigateTo("sheet") }
            advanceUntilIdle()

            store.navigation {
                navigateTo("home")
                dismissModals()
            }
            advanceUntilIdle()

            assertEquals(listOf("home"), store.routes())
        }

    @Test
    fun `a system alert over a modal keeps the modal and its screen visible`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val store = storeWith(basicModule())
            advanceUntilIdle()
            store.navigation { navigateTo("sheet") }
            store.navigation { navigateTo("alert") }
            advanceUntilIdle()

            assertEquals(listOf("home", "sheet", "alert"), store.nav().visibleLayers.map { it.route })
        }

    @Test
    fun `a path param wins over a query param with the same name`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val item = screen("item/{id}")
            val store = storeWith(createNavigationModule {
                rootGraph {
                    start(home)
                    screens(home, item)
                }
            })
            advanceUntilIdle()

            store.navigateDeepLink("item/42?id=99")
            advanceUntilIdle()

            assertEquals("42", store.nav().currentEntry.params.getString("id"))
        }

    @Test
    fun `re-adding a screen while its old copy is still leaving runs the old removal first`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val events = mutableListOf<String>()
            val detail = screen("detail", forwardMs = 300, popMs = 300, onCreated = { lifecycle ->
                events += "created"
                lifecycle.invokeOnRemoval { events += "removed" }
            })
            val store = storeWith(createNavigationModule {
                rootGraph {
                    start(home)
                    screens(home, detail)
                }
            })
            advanceUntilIdle()
            store.navigation { navigateTo("detail") }
            advanceUntilIdle()

            store.navigateBack()
            advanceTimeBy(50)
            store.navigation { navigateTo("detail") }
            advanceUntilIdle()

            assertEquals(listOf("created", "removed", "created"), events)
        }

    @Test
    fun `a removal handler that throws still lets the others run and ends the lifecycle`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val events = mutableListOf<String>()
            var lifecycleWork: Job? = null
            val fragile = screen("fragile", onCreated = { lifecycle ->
                lifecycleWork = lifecycle.launch { delay(Long.MAX_VALUE) }
                lifecycle.invokeOnRemoval { throw IllegalStateException("broken handler") }
                lifecycle.invokeOnRemoval { events += "second handler" }
            })
            val store = storeWith(createNavigationModule {
                rootGraph {
                    start(home)
                    screens(home, fragile, profile)
                }
            })
            advanceUntilIdle()
            store.navigation { navigateTo("fragile") }
            advanceUntilIdle()

            store.navigateBack()
            advanceUntilIdle()
            store.navigation { navigateTo("profile") }
            advanceUntilIdle()

            assertEquals(listOf("second handler"), events)
            assertTrue(lifecycleWork?.isCancelled == true, "the lifecycle scope must end")
            assertEquals("profile", store.nav().currentEntry.route)
        }

    @Test
    fun `a sensitive named path param survives a history snapshot`() {
        val invite = screen("invite/{token}")
        val module = createNavigationModule {
            rootGraph {
                start(home)
                screens(home, invite)
            }
        }
        val codec = LocationCodec(module.precomputedData, Json)

        val snapshot = codec.snapshotOf(listOf(NavigationEntry(invite, "invite/{token}", Params.of("token" to "abc"))))
        val restored = codec.entriesOf(snapshot, emptyList())

        assertEquals("abc", restored?.single()?.params?.getString("token"))
    }
}
