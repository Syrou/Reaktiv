import io.github.syrou.reaktiv.core.Store
import io.github.syrou.reaktiv.core.createStore
import io.github.syrou.reaktiv.core.util.selectLogic
import io.github.syrou.reaktiv.navigation.NavigationLogic
import io.github.syrou.reaktiv.navigation.NavigationOutcome
import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.createNavigationModule
import io.github.syrou.reaktiv.navigation.extension.navigation
import io.github.syrou.reaktiv.navigation.model.GuardResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.DurationUnit
import kotlin.time.toDuration

@OptIn(ExperimentalCoroutinesApi::class)
class GuardRedirectStepsTest {

    private val home = historyScreen("home")
    private val list = historyScreen("list")
    private val detail = historyScreen("detail")
    private val notReady = historyScreen("not-ready")
    private val after = historyScreen("after")
    private val login = historyScreen("login")
    private var decision: GuardResult = GuardResult.RedirectTo(notReady)

    private fun TestScope.store() = createStore {
        module(
            createNavigationModule {
                rootGraph {
                    start(home)
                    screens(home, list, detail, notReady, after, login)
                    intercept(guard = { _ -> decision }) {
                        screens(historyScreen("guarded"))
                    }
                }
            }
        )
        coroutineContext(StandardTestDispatcher(testScheduler))
    }

    private suspend fun Store.routes() = selectState<NavigationState>().first().backStack.map { it.route }

    private suspend fun TestScope.onDetail(store: Store) {
        advanceUntilIdle()
        store.navigation { navigateTo("list") }
        store.navigation { navigateTo("detail") }
        advanceUntilIdle()
    }

    @Test
    fun `a redirect keeps the steps before the screen it refuses`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val store = store()
            onDetail(store)

            val outcome = store.selectLogic<NavigationLogic>().navigate {
                popUpTo("list")
                navigateTo("guarded")
            }
            advanceUntilIdle()

            assertEquals(NavigationOutcome.Redirected("not-ready"), outcome)
            assertEquals(listOf("home", "list", "not-ready"), store.routes())
        }

    @Test
    fun `a redirect drops the steps after the screen it refuses`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val store = store()
            onDetail(store)

            store.navigation {
                navigateTo("guarded")
                navigateTo("after")
            }
            advanceUntilIdle()

            assertEquals(listOf("home", "list", "detail", "not-ready"), store.routes())
        }

    @Test
    fun `a redirect after clearing the back stack lands on a cleared stack`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val store = store()
            onDetail(store)

            store.navigation {
                clearBackStack()
                navigateTo("guarded")
            }
            advanceUntilIdle()

            assertEquals(listOf("not-ready"), store.routes())
        }

    @Test
    fun `a pending redirect still reroutes the whole navigation`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            decision = GuardResult.PendAndRedirectTo(login)
            val store = store()
            onDetail(store)

            store.navigation {
                popUpTo("list")
                navigateTo("guarded")
            }
            advanceUntilIdle()

            assertEquals(listOf("login"), store.routes())
        }
}
