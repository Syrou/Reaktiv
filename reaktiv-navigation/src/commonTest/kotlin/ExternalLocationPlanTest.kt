import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import io.github.syrou.reaktiv.core.Store
import io.github.syrou.reaktiv.core.createStore
import io.github.syrou.reaktiv.core.util.selectLogic
import io.github.syrou.reaktiv.navigation.NavigationAction
import io.github.syrou.reaktiv.navigation.NavigationLogic
import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.TraverseDirection
import io.github.syrou.reaktiv.navigation.TraversePresentation
import io.github.syrou.reaktiv.navigation.createNavigationModule
import io.github.syrou.reaktiv.navigation.definition.LoadingModal
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.extension.navigation
import io.github.syrou.reaktiv.navigation.history.ExternalLocation
import io.github.syrou.reaktiv.navigation.history.ExternalOutcome
import io.github.syrou.reaktiv.navigation.history.LocationSnapshot
import io.github.syrou.reaktiv.navigation.history.SnapshotEntry
import io.github.syrou.reaktiv.navigation.model.GuardResult
import io.github.syrou.reaktiv.navigation.param.Params
import io.github.syrou.reaktiv.navigation.transition.NavTransition
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.DurationUnit
import kotlin.time.toDuration

@OptIn(ExperimentalCoroutinesApi::class)
class ExternalLocationPlanTest {

    private fun screen(route: String) = object : Screen {
        override val route = route
        override val enterTransition = NavTransition.None
        override val exitTransition = NavTransition.None

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
            Text("loading")
        }
    }

    private val home = screen("home")
    private val help = screen("help")
    private val login = screen("login")
    private val invitation = screen("invitation/{token}")
    private val dashboard = screen("dashboard")
    private val adminUser = screen("user/{id}")
    private val storefront = screen("front")

    private var guardCalls = 0
    private var entryCalls = 0
    private var decision: GuardResult = GuardResult.Allow
    private var guardGate: CompletableDeferred<Unit>? = null
    private var guardDelayMs = 0L

    private fun TestScope.store() = createStore {
        module(
            createNavigationModule {
                loadingModal(loading)
                deepLinkAliases {
                    alias("{scheme}://{host}/invite/{token}", "invitation/{token}")
                }
                rootGraph {
                    start(home)
                    screens(home, help, login, invitation)
                    intercept(guard = { _ ->
                        guardCalls++
                        guardGate?.await()
                        delay(guardDelayMs)
                        decision
                    }) {
                        graph("admin") {
                            start(dashboard)
                            screens(dashboard, adminUser)
                        }
                    }
                    graph("shop") {
                        start(route = { _ ->
                            entryCalls++
                            storefront
                        })
                        screens(storefront)
                    }
                }
            }
        )
        coroutineContext(StandardTestDispatcher(testScheduler))
    }

    private class Captured(val snapshot: LocationSnapshot, val url: String)

    private suspend fun Store.logic() = selectLogic<NavigationLogic>()

    private suspend fun Store.state() = selectState<NavigationState>().first()

    private suspend fun Store.capture(): Captured {
        val stack = state().backStack
        return Captured(logic().locationCodec.snapshotOf(stack), stack.last().location)
    }

    private suspend fun Store.restore(
        captured: Captured,
        direction: TraverseDirection = TraverseDirection.Forward
    ): ExternalOutcome = logic().applyExternalLocation(
        ExternalLocation.Snapshot(
            captured.snapshot,
            ExternalLocation.Url(captured.url),
            direction,
            TraversePresentation.Animate
        )
    )

    private suspend fun Store.locations() = state().backStack.map { it.location }

    @Test
    fun `a snapshot the stack already covers goes back without asking any guard`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val store = store()
            advanceUntilIdle()
            val atHome = store.capture()
            store.navigation { navigateTo("admin/dashboard") }
            store.navigation { navigateTo(adminUser, "id" to 7) }
            advanceUntilIdle()
            guardCalls = 0

            val outcome = store.restore(atHome, TraverseDirection.Back)
            advanceUntilIdle()

            assertEquals(ExternalOutcome.Landed, outcome)
            assertEquals(listOf("home"), store.locations())
            assertEquals(0, guardCalls)
            val action = store.state().lastNavigationAction
            assertIs<NavigationAction.Traverse>(action)
            assertEquals(TraverseDirection.Back, action.direction)
        }

    @Test
    fun `a snapshot above the stack lands exactly and asks each guarded zone once`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val store = store()
            advanceUntilIdle()
            store.navigation { navigateTo("admin/dashboard") }
            store.navigation { navigateTo(adminUser, "id" to 7) }
            advanceUntilIdle()
            val deep = store.capture()
            store.navigation { popUpTo("home") }
            advanceUntilIdle()
            guardCalls = 0

            val outcome = store.restore(deep)
            advanceUntilIdle()

            assertEquals(ExternalOutcome.Landed, outcome)
            assertEquals(listOf("home", "admin/dashboard", "admin/user/7"), store.locations())
            assertEquals(1, guardCalls)
        }

    @Test
    fun `a restore whose guard outlasts the loading threshold clears the loading overlay`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val store = store()
            advanceUntilIdle()
            store.navigation { navigateTo("admin/dashboard") }
            advanceUntilIdle()
            val deep = store.capture()
            store.navigation { popUpTo("home") }
            advanceUntilIdle()
            guardDelayMs = 1_000

            val outcome = store.restore(deep)
            advanceUntilIdle()

            assertEquals(ExternalOutcome.Landed, outcome)
            assertEquals(listOf("home", "admin/dashboard"), store.locations())
            assertEquals(false, store.state().isEvaluatingNavigation)
        }

    @Test
    fun `a guarded entry beneath the restored top is still guarded`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val store = store()
            advanceUntilIdle()
            store.navigation { navigateTo("admin/dashboard") }
            store.navigation { navigateTo("help") }
            advanceUntilIdle()
            val withAdminBeneath = store.capture()
            store.navigation { popUpTo("home") }
            advanceUntilIdle()
            decision = GuardResult.Reject

            val outcome = store.restore(withAdminBeneath)
            advanceUntilIdle()

            assertEquals(ExternalOutcome.Rejected, outcome)
            assertEquals(listOf("home"), store.locations())
        }

    @Test
    fun `a guard redirect lands on top of the part of the snapshot that passed`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val store = store()
            advanceUntilIdle()
            store.navigation { navigateTo("help") }
            store.navigation { navigateTo("admin/dashboard") }
            advanceUntilIdle()
            val deep = store.capture()
            store.navigation { popUpTo("home") }
            advanceUntilIdle()
            decision = GuardResult.RedirectTo(login)

            val outcome = store.restore(deep)
            advanceUntilIdle()

            assertEquals(ExternalOutcome.Redirected("login"), outcome)
            assertEquals(listOf("home", "help", "login"), store.locations())
        }

    @Test
    fun `a pending redirect resumes at the top of the snapshot`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val store = store()
            advanceUntilIdle()
            store.navigation { navigateTo("admin/dashboard") }
            store.navigation { navigateTo(adminUser, "id" to 7) }
            advanceUntilIdle()
            val deep = store.capture()
            store.navigation { popUpTo("home") }
            advanceUntilIdle()
            decision = GuardResult.PendAndRedirectTo(login)

            val outcome = store.restore(deep)
            advanceUntilIdle()

            assertEquals(ExternalOutcome.Redirected("login"), outcome)
            val state = store.state()
            assertEquals("login", state.currentEntry.location)
            assertEquals("admin/user/7", state.pendingNavigation?.route)
        }

    @Test
    fun `restored entries do not run their graph start again`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val store = store()
            advanceUntilIdle()
            store.navigation { navigateTo("shop") }
            advanceUntilIdle()
            val inShop = store.capture()
            store.navigation { popUpTo("home") }
            advanceUntilIdle()
            entryCalls = 0

            val outcome = store.restore(inShop)
            advanceUntilIdle()

            assertEquals(ExternalOutcome.Landed, outcome)
            assertEquals(listOf("home", "shop/front"), store.locations())
            assertEquals(0, entryCalls)
        }

    @Test
    fun `a restore abandons its commit when the stack moved while a guard was running`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val store = store()
            advanceUntilIdle()
            store.navigation { navigateTo("admin/dashboard") }
            advanceUntilIdle()
            val deep = store.capture()
            store.navigation { popUpTo("home") }
            store.navigation { navigateTo("help") }
            advanceUntilIdle()
            val gate = CompletableDeferred<Unit>()
            guardGate = gate

            val outcome = async { store.restore(deep) }
            runCurrent()
            store.navigation { navigateBack() }
            runCurrent()
            gate.complete(Unit)
            advanceUntilIdle()

            assertEquals(ExternalOutcome.Stale, outcome.await())
            assertEquals(listOf("home"), store.locations())
        }

    @Test
    fun `a snapshot that no longer decodes falls back to its url`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val store = store()
            advanceUntilIdle()
            val gone = Captured(LocationSnapshot(listOf(SnapshotEntry("gone", JsonObject(emptyMap())))), "help")

            val outcome = store.restore(gone)
            advanceUntilIdle()

            assertEquals(ExternalOutcome.Landed, outcome)
            assertEquals("help", store.state().currentEntry.location)
        }

    @Test
    fun `an alias that names a scheme and host matches the absolute href`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val store = store()
            advanceUntilIdle()

            val outcome = store.logic().applyExternalLocation(
                ExternalLocation.Url("invite/abc", href = "https://example.com/invite/abc?utm=x")
            )
            advanceUntilIdle()

            assertEquals(ExternalOutcome.Landed, outcome)
            assertEquals("invitation/abc", store.state().currentEntry.location)
        }

    @Test
    fun `a url that resolves to nothing is reported rather than thrown`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val store = store()
            advanceUntilIdle()

            val outcome = store.logic().applyExternalLocation(ExternalLocation.Url("nowhere/at/all"))
            advanceUntilIdle()

            assertIs<ExternalOutcome.Unresolvable>(outcome)
            assertEquals(listOf("home"), store.locations())
        }

    @Test
    fun `a restore from an empty common prefix follows the url when the start destination changed`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            var loggedIn = true
            val store = createStore {
                module(
                    createNavigationModule {
                        loadingModal(loading)
                        rootGraph {
                            start(route = { _ -> if (loggedIn) home else login })
                            screens(home, help, login)
                        }
                    }
                )
                coroutineContext(StandardTestDispatcher(testScheduler))
            }
            advanceUntilIdle()
            store.navigation { navigateTo("help") }
            advanceUntilIdle()
            val fromHome = store.capture()
            store.navigation {
                clearBackStack()
                navigateTo("login")
            }
            advanceUntilIdle()

            loggedIn = false
            store.restore(fromHome)
            advanceUntilIdle()
            assertEquals(listOf("login", "help"), store.locations())
            assertTrue(store.state().lastNavigationAction !is NavigationAction.Traverse)

            store.navigation {
                clearBackStack()
                navigateTo("login")
            }
            advanceUntilIdle()
            loggedIn = true
            store.restore(fromHome)
            advanceUntilIdle()
            assertEquals(listOf("home", "help"), store.locations())
            assertIs<NavigationAction.Traverse>(store.state().lastNavigationAction)
        }
}
