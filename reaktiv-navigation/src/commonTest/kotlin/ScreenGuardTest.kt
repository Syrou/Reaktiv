import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import io.github.syrou.reaktiv.core.Store
import io.github.syrou.reaktiv.core.createStore
import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.createNavigationModule
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.extension.navigateBack
import io.github.syrou.reaktiv.navigation.extension.navigation
import io.github.syrou.reaktiv.navigation.extension.openLink
import io.github.syrou.reaktiv.navigation.link.LinkOutcome
import io.github.syrou.reaktiv.navigation.model.GuardResult
import io.github.syrou.reaktiv.navigation.model.NavigationGuard
import io.github.syrou.reaktiv.navigation.param.Params
import io.github.syrou.reaktiv.navigation.transition.NavTransition
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.time.DurationUnit
import kotlin.time.toDuration

@OptIn(ExperimentalCoroutinesApi::class)
class ScreenGuardTest {

    private val calls = mutableListOf<String>()
    private var signedIn = true
    private var hasFeature = true

    private fun guardedScreen(route: String, guard: NavigationGuard): Screen = object : Screen {
        override val route = route
        override val enterTransition = NavTransition.None
        override val exitTransition = NavTransition.None
        override val guard: NavigationGuard = guard

        @Composable
        override fun Content(params: Params) {
            Text(route)
        }
    }

    private val home = historyScreen("home")
    private val login = historyScreen("login")
    private val upsell = historyScreen("upsell")
    private val list = historyScreen("list")
    private val settings = historyScreen("settings")
    private val feature = guardedScreen("feature") { _ ->
        calls += "screen:${params.getString("itemId")}"
        if (hasFeature) GuardResult.Allow else GuardResult.RedirectTo(upsell, Params.of("source" to "feature"))
    }
    private val overview = guardedScreen("overview") { _ ->
        calls += "overview"
        GuardResult.Allow
    }
    private val detail = historyScreen("detail")
    private val refusing = guardedScreen("refusing") { _ ->
        calls += "refusing"
        GuardResult.Reject
    }
    private val sendsToRefusing = guardedScreen("sends-to-refusing") { _ ->
        GuardResult.RedirectTo(refusing)
    }

    private fun TestScope.store() = createStore {
        module(
            createNavigationModule {
                rootGraph {
                    start(home)
                    screens(home, login, upsell, refusing)
                    intercept(
                        guard = { _ ->
                            calls += "zone"
                            if (signedIn) GuardResult.Allow
                            else GuardResult.PendAndRedirectTo(login, params = Params.of("reason" to "signed-out"))
                        }
                    ) {
                        screens(list, feature, settings, sendsToRefusing)
                        graph("workspace") {
                            start(overview)
                            screens(overview, detail)
                        }
                    }
                }
            }
        )
        coroutineContext(StandardTestDispatcher(testScheduler))
    }

    private suspend fun Store.navigationState() = selectState<NavigationState>().first()

    @Test
    fun `a screen guard runs after the intercept guards around it`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val store = store()
            advanceUntilIdle()

            store.navigation { navigateTo("feature?itemId=7") }
            advanceUntilIdle()

            assertEquals("feature", store.navigationState().currentEntry.route)
            assertEquals(listOf("zone", "screen:7"), calls)
        }

    @Test
    fun `a screen guard does not run when an intercept guard around it stops the navigation`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            signedIn = false
            val store = store()
            advanceUntilIdle()

            store.navigation { navigateTo("feature?itemId=7") }
            advanceUntilIdle()

            assertEquals("login", store.navigationState().currentEntry.route)
            assertEquals(listOf("zone"), calls)
        }

    @Test
    fun `a screen guard runs every time the screen is entered but not when the user goes back to it`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val store = store()
            advanceUntilIdle()

            store.navigation { navigateTo("list") }
            advanceUntilIdle()
            store.navigation { navigateTo("feature?itemId=1") }
            advanceUntilIdle()
            store.navigation { navigateTo("settings") }
            advanceUntilIdle()
            store.navigateBack()
            advanceUntilIdle()
            assertEquals("feature", store.navigationState().currentEntry.route)

            store.navigateBack()
            advanceUntilIdle()
            store.navigation { navigateTo("feature?itemId=2") }
            advanceUntilIdle()

            assertEquals(listOf("screen:1", "screen:2"), calls.filter { it.startsWith("screen") })
        }

    @Test
    fun `a screen guard redirects with the params it gives`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            hasFeature = false
            val store = store()
            advanceUntilIdle()

            store.navigation { navigateTo("list") }
            advanceUntilIdle()
            store.navigation { navigateTo("feature?itemId=7") }
            advanceUntilIdle()

            val state = store.navigationState()
            assertEquals("upsell", state.currentEntry.route)
            assertEquals("feature", state.currentEntry.params.getString("source"))
            assertEquals(listOf("home", "list", "upsell"), state.backStack.map { it.route })
        }

    @Test
    fun `a link runs the screen guard of the screen it lands on`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            hasFeature = false
            val store = store()
            advanceUntilIdle()

            store.openLink("feature?itemId=9")
            advanceUntilIdle()

            assertEquals("upsell", store.navigationState().currentEntry.route)
            assertEquals(listOf("zone", "screen:9"), calls)
        }

    @Test
    fun `a link at cold start runs the screen guard of the screen it lands on`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            hasFeature = false
            val store = store()

            val link = async { store.openLink("feature?itemId=3") }
            advanceUntilIdle()

            assertEquals(LinkOutcome.Redirected("upsell"), link.await())
            val state = store.navigationState()
            assertEquals("upsell", state.currentEntry.route)
            assertEquals("feature", state.currentEntry.params.getString("source"))
            assertFalse(state.isBootstrapping)
            assertEquals(listOf("zone", "screen:3"), calls)
        }

    @Test
    fun `a pending navigation runs the screen guard when it resumes`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            signedIn = false
            val store = store()
            advanceUntilIdle()

            store.openLink("feature?itemId=4")
            advanceUntilIdle()
            val atLogin = store.navigationState().currentEntry
            assertEquals("login", atLogin.route)
            assertEquals("signed-out", atLogin.params.getString("reason"))
            assertEquals(listOf("zone"), calls)

            signedIn = true
            store.navigation {
                clearBackStack()
                resumePendingNavigation()
            }
            advanceUntilIdle()

            assertEquals("feature", store.navigationState().currentEntry.route)
            assertEquals(listOf("zone", "zone", "screen:4"), calls)
        }

    @Test
    fun `a screen with a guard is not built beneath a linked destination`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val store = store()
            advanceUntilIdle()

            store.openLink("workspace/detail")
            advanceUntilIdle()

            val state = store.navigationState()
            assertEquals("detail", state.currentEntry.route)
            assertFalse("overview" in state.backStack.map { it.route })
            assertFalse("overview" in calls)
        }

    @Test
    fun `a screen guard runs once for a graph whose start is that screen`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val store = store()
            advanceUntilIdle()

            store.navigation { navigateTo("workspace") }
            advanceUntilIdle()

            assertEquals("overview", store.navigationState().currentEntry.route)
            assertEquals(listOf("zone", "overview"), calls)
        }

    @Test
    fun `the destination a guard redirects to opens without its own guard`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val store = store()
            advanceUntilIdle()

            store.navigation { navigateTo("sends-to-refusing") }
            advanceUntilIdle()

            assertEquals("refusing", store.navigationState().currentEntry.route)
            assertFalse("refusing" in calls)
        }
}
