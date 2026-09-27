import io.github.syrou.reaktiv.core.createStore
import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.createNavigationModule
import io.github.syrou.reaktiv.navigation.extension.navigation
import io.github.syrou.reaktiv.navigation.history.UrlStyle
import io.github.syrou.reaktiv.navigation.model.GuardResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.DurationUnit
import kotlin.time.toDuration

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryPendingTest {

    private val home = historyScreen("home")
    private val login = historyScreen("login")
    private val dashboard = historyScreen("dashboard")
    private var signedIn = false

    private fun TestScope.store(browser: FakeBrowser) = createStore {
        module(
            createNavigationModule {
                browserHistoryForTesting(browser, UrlStyle.Hash)
                rootGraph {
                    start(home)
                    screens(home, login)
                    intercept(guard = { _ ->
                        if (signedIn) GuardResult.Allow else GuardResult.PendAndRedirectTo(login)
                    }) {
                        graph("admin") {
                            start(dashboard)
                            screens(dashboard)
                        }
                    }
                }
            }
        )
        coroutineContext(StandardTestDispatcher(testScheduler))
    }

    @Test
    fun `a pending navigation survives leaving the page and resumes after sign in`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val browser = fakeBrowser()
            val first = store(browser)
            advanceUntilIdle()
            first.navigation {
                navigateTo("admin") {
                    put("tab", "billing")
                }
            }
            advanceUntilIdle()
            assertEquals("login", first.selectState<NavigationState>().first().currentEntry.route)
            first.cleanup()
            browser.reload()

            val second = store(browser)
            advanceUntilIdle()
            val restored = second.selectState<NavigationState>().first()
            assertEquals("login", restored.currentEntry.route)
            assertEquals("admin", restored.pendingNavigation?.route)
            assertEquals("billing", restored.pendingNavigation?.params?.getString("tab"))

            signedIn = true
            second.navigation {
                clearBackStack()
                resumePendingNavigation()
            }
            advanceUntilIdle()

            assertEquals("dashboard", second.selectState<NavigationState>().first().currentEntry.route)
            assertTrue(browser.session.isEmpty())
        }

    @Test
    fun `secrets in a pending navigation are never stored`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val browser = fakeBrowser()
            val store = store(browser)
            advanceUntilIdle()

            store.navigation {
                navigateTo("admin") {
                    put("accessToken", "secret-value")
                    put("tab", "billing")
                }
            }
            advanceUntilIdle()

            val stored = browser.session.values.single()
            assertFalse("secret-value" in stored)
            assertTrue("billing" in stored)
        }

    @Test
    fun `a reset forgets the stored pending navigation`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val browser = fakeBrowser()
            val store = store(browser)
            advanceUntilIdle()
            store.navigation { navigateTo("admin") }
            advanceUntilIdle()
            assertEquals(1, browser.session.size)

            store.reset()
            advanceUntilIdle()

            assertTrue(browser.session.isEmpty())
        }
}
