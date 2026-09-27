import io.github.syrou.reaktiv.core.Store
import io.github.syrou.reaktiv.core.createStore
import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.createNavigationModule
import io.github.syrou.reaktiv.navigation.extension.openLink
import io.github.syrou.reaktiv.navigation.history.UrlStyle
import io.github.syrou.reaktiv.navigation.link.LinkOutcome
import io.github.syrou.reaktiv.navigation.model.GuardResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.DurationUnit
import kotlin.time.toDuration

@OptIn(ExperimentalCoroutinesApi::class)
class OpenLinkTest {

    private val home = historyScreen("home")
    private val settings = historyScreen("settings")
    private val login = historyScreen("login")
    private val feed = historyScreen("feed")
    private val article = historyScreen("article/{slug}")
    private val profile = historyScreen("profile/{userId}")
    private val reports = historyScreen("reports")
    private val missing = historyScreen("missing")
    private var decision: GuardResult = GuardResult.Allow

    private fun TestScope.store(withNotFound: Boolean = false, browser: FakeBrowser? = null) = createStore {
        module(
            createNavigationModule {
                if (browser != null) browserHistoryForTesting(browser)
                if (withNotFound) notFoundScreen(missing)
                deepLinkAliases {
                    alias("{scheme}://{host}/p/{userId}", "profile/{userId}")
                }
                rootGraph {
                    start(home)
                    screens(home, settings, login, profile)
                    graph("news") {
                        start(feed)
                        screens(feed, article)
                    }
                    intercept(guard = { _ -> decision }) {
                        graph("admin") {
                            start(reports)
                            screens(reports)
                        }
                    }
                }
            }
        )
        coroutineContext(StandardTestDispatcher(testScheduler))
    }

    private suspend fun Store.current() =
        selectState<NavigationState>().first().currentEntry

    @Test
    fun `a template link fills its params and lands with parents beneath`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val store = store()
            advanceUntilIdle()

            val outcome = store.openLink("news/article/{slug}", mapOf("slug" to "hello world"))
            advanceUntilIdle()

            assertEquals(LinkOutcome.Landed("news/article/hello%20world"), outcome)
            assertEquals("hello world", store.current().params.getString("slug"))
            assertEquals("news/article/hello%20world", store.locations().last())
            assertTrue("news/feed" in store.locations())
        }

    @Test
    fun `a template link without its params reports which ones are missing`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val store = store()
            advanceUntilIdle()

            val outcome = store.openLink("news/article/{slug}")

            assertEquals(LinkOutcome.MissingParams(listOf("slug")), outcome)
            assertEquals("home", store.current().path)
        }

    @Test
    fun `a concrete link with a query lands and keeps the query as params`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val store = store()
            advanceUntilIdle()

            val outcome = store.openLink("settings?tab=privacy")
            advanceUntilIdle()

            assertEquals(LinkOutcome.Landed("settings"), outcome)
            assertEquals("privacy", store.current().params.getString("tab"))
        }

    @Test
    fun `an alias link lands on its target`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val store = store()
            advanceUntilIdle()

            val outcome = store.openLink("reaktiv://example.com/p/42")
            advanceUntilIdle()

            assertEquals(LinkOutcome.Landed("profile/42"), outcome)
        }

    @Test
    fun `a web address lands on the path under the app base`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val browser = FakeBrowser(StandardTestDispatcher(testScheduler), FakeBrowser.ORIGIN + "/app/", basePath = "/app/")
            val store = store(browser = browser)
            advanceUntilIdle()

            val outcome = store.openLink("https://example.com/app/news/feed")
            advanceUntilIdle()

            assertEquals(LinkOutcome.Landed("news/feed"), outcome)
        }

    @Test
    fun `a guard that redirects is reported as a redirect`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            decision = GuardResult.RedirectTo(login)
            val store = store()
            advanceUntilIdle()

            val outcome = store.openLink("admin/reports")
            advanceUntilIdle()

            assertIs<LinkOutcome.Redirected>(outcome)
            assertEquals("login", store.current().path)
        }

    @Test
    fun `a guard that rejects is reported as rejected`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            decision = GuardResult.Reject
            val store = store()
            advanceUntilIdle()

            val outcome = store.openLink("admin/reports")
            advanceUntilIdle()

            assertEquals(LinkOutcome.Rejected, outcome)
        }

    @Test
    fun `an unknown link is not found without a not found screen`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val store = store()
            advanceUntilIdle()

            val outcome = store.openLink("no/such/place")

            assertIs<LinkOutcome.NotFound>(outcome)
            assertEquals("home", store.current().path)
        }

    @Test
    fun `an unknown link that lands on the not found screen still reports not found`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val store = store(withNotFound = true)
            advanceUntilIdle()

            val outcome = store.openLink("no/such/place")
            advanceUntilIdle()

            assertIs<LinkOutcome.NotFound>(outcome)
            assertEquals("missing", store.current().path)
        }

    @Test
    fun `hash mode links still land`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val store = createStore {
                module(
                    createNavigationModule {
                        browserHistoryForTesting(fakeBrowser(), UrlStyle.Hash)
                        rootGraph {
                            start(home)
                            screens(home, settings)
                        }
                    }
                )
                coroutineContext(StandardTestDispatcher(testScheduler))
            }
            advanceUntilIdle()

            assertEquals(LinkOutcome.Landed("settings"), store.openLink("settings"))
        }
}
