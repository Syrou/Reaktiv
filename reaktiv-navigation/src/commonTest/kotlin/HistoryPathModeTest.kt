import io.github.syrou.reaktiv.core.createStore
import io.github.syrou.reaktiv.navigation.NavigationAction
import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.createNavigationModule
import io.github.syrou.reaktiv.navigation.extension.navigation
import io.github.syrou.reaktiv.navigation.history.UrlStyle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.DurationUnit
import kotlin.time.toDuration

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryPathModeTest {

    private val home = historyScreen("home")
    private val a = historyScreen("a")
    private val user = historyScreen("user/{id}")
    private val dashboard = historyScreen("dashboard")

    private fun TestScope.store(browser: FakeBrowser, style: UrlStyle? = null) = createStore {
        module(
            createNavigationModule {
                if (style == null) browserHistoryForTesting(browser) else browserHistoryForTesting(browser, style)
                rootGraph {
                    start(home)
                    screens(home, a, user)
                    graph("admin") {
                        start(dashboard)
                        screens(dashboard)
                    }
                }
            }
        )
        coroutineContext(StandardTestDispatcher(testScheduler))
    }

    private fun TestScope.browserAt(path: String, basePath: String = "/") =
        FakeBrowser(StandardTestDispatcher(testScheduler), FakeBrowser.ORIGIN + path, basePath = basePath)

    private val FakeBrowser.paths: List<String>
        get() = entries.map { it.url.removePrefix(FakeBrowser.ORIGIN) }

    @Test
    fun `path urls are the default`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val browser = browserAt("/")
            store(browser)
            advanceUntilIdle()

            assertEquals(listOf("/home"), browser.paths)
            assertEquals(listOf("replace /home"), browser.log)
        }

    @Test
    fun `each navigation pushes a path`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val browser = browserAt("/")
            val store = store(browser)
            advanceUntilIdle()

            store.navigation { navigateTo("a") }
            store.navigation { navigateTo(user, "id" to "naïve café") }
            advanceUntilIdle()

            assertEquals(listOf("/home", "/a", "/user/na%C3%AFve%20caf%C3%A9"), browser.paths)
        }

    @Test
    fun `paths are written under the base the app is served from`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val browser = browserAt("/app/", basePath = "/app/")
            val store = store(browser)
            advanceUntilIdle()

            store.navigation { navigateTo(user, "id" to 7) }
            advanceUntilIdle()

            assertEquals(listOf("/app/home", "/app/user/7"), browser.paths)
        }

    @Test
    fun `query params travel with their route`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val browser = browserAt("/")
            val store = store(browser)
            advanceUntilIdle()

            store.navigation { navigateTo(a) { put("tab", "stats") } }
            advanceUntilIdle()

            assertEquals("/a?tab=stats", browser.paths.last())
        }

    @Test
    fun `a shared deep link lands with its parents and keeps its path`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val browser = browserAt("/admin/dashboard")
            val store = store(browser)
            advanceUntilIdle()

            assertEquals(listOf("home", "admin/dashboard"), store.locations())
            assertEquals(listOf("/admin/dashboard"), browser.paths)
        }

    @Test
    fun `back and forward restore the stacks their paths name`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val browser = browserAt("/")
            val store = store(browser)
            advanceUntilIdle()
            store.navigation { navigateTo("a") }
            store.navigation { navigateTo(user, "id" to 3) }
            advanceUntilIdle()

            browser.back()
            advanceUntilIdle()
            assertEquals(listOf("home", "a"), store.locations())

            browser.forward()
            advanceUntilIdle()
            assertEquals(listOf("home", "a", "user/3"), store.locations())
            assertEquals(3, browser.entries.size)
        }

    @Test
    fun `a reload on a nested path restores the exact stack`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val browser = browserAt("/")
            val first = store(browser)
            advanceUntilIdle()
            first.navigation { navigateTo(user, "id" to 7) }
            advanceUntilIdle()
            first.cleanup()
            browser.reload()

            val second = store(browser)
            advanceUntilIdle()

            val state = second.selectState<NavigationState>().first()
            assertEquals(listOf("home", "user/7"), state.backStack.map { it.location })
            assertIs<NavigationAction.Traverse>(state.lastNavigationAction)
            assertEquals("/user/7", browser.paths.last())
        }

    @Test
    fun `hash urls remain available as an option`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val browser = browserAt("/")
            val store = store(browser, UrlStyle.Hash)
            advanceUntilIdle()

            store.navigation { navigateTo("a") }
            advanceUntilIdle()

            assertEquals(listOf("/#/home", "/#/a"), browser.paths)
        }
}
