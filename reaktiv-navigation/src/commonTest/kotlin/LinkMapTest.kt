import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import io.github.syrou.reaktiv.core.util.reaktivJson
import io.github.syrou.reaktiv.navigation.NavigationModule
import io.github.syrou.reaktiv.navigation.createNavigationModule
import io.github.syrou.reaktiv.navigation.definition.LoadingModal
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.history.UrlStyle
import io.github.syrou.reaktiv.navigation.layer.RenderLayer
import io.github.syrou.reaktiv.navigation.link.LinkAccess
import io.github.syrou.reaktiv.navigation.link.LinkAlias
import io.github.syrou.reaktiv.navigation.link.LinkKind
import io.github.syrou.reaktiv.navigation.link.LinkStart
import io.github.syrou.reaktiv.navigation.link.NavigationLinkMap
import io.github.syrou.reaktiv.navigation.model.GuardResult
import io.github.syrou.reaktiv.navigation.model.NavigationGuard
import io.github.syrou.reaktiv.navigation.param.Params
import io.github.syrou.reaktiv.navigation.transition.NavTransition
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

object LinkProfileScreen : Screen {
    override val route = "profile/{userId}"
    override val enterTransition = NavTransition.None
    override val exitTransition = NavTransition.None

    @Composable
    override fun Content(params: Params) {
        Text(route)
    }
}

class LinkMapTest {

    private val home = historyScreen("home")
    private val settings = historyScreen("settings")
    private val byId = historyScreen("user/{id}")
    private val byUid = historyScreen("user/{uid}")
    private val feed = historyScreen("feed")
    private val article = historyScreen("article/{slug}")
    private val step = historyScreen("step")
    private val reports = historyScreen("reports")
    private val audit = historyScreen("audit")
    private val vip = historyScreen("vip")
    private val overview = historyScreen("overview")
    private val missing = historyScreen("missing")
    private val crash = historyScreen("crash")
    private val sheet = historyModal("sheet")
    private val alert = historyModal("alert", RenderLayer.SYSTEM)
    private val loading = object : LoadingModal {
        override val route = "loading"
        override val enterTransition = NavTransition.None
        override val exitTransition = NavTransition.None

        @Composable
        override fun Content(params: Params) {
            Text("loading")
        }
    }

    private val adminGuard: NavigationGuard = { GuardResult.Allow }
    private val auditGuard: NavigationGuard = { GuardResult.Allow }

    private fun module(): NavigationModule = createNavigationModule {
        notFoundScreen(missing)
        crashScreen(crash)
        loadingModal(loading)
        deepLinkAliases {
            alias("{scheme}://{host}/p/{userId}", "profile/{userId}")
        }
        rootGraph {
            start(home)
            screens(home, settings, byId, byUid, LinkProfileScreen)
            modals(sheet, alert)
            graph("news") {
                start(feed)
                screens(feed, article)
            }
            graph("wizard") {
                start(step)
                screens(step)
                graph("nested") {
                    start(step)
                    screens(step)
                }
            }
            graph("dynamic") {
                start(route = { _ -> reports })
                screens(reports)
            }
            graph("umbrella") {
                graph("inner") {
                    start(overview)
                    screens(overview)
                }
            }
            graph("hub") {
                start("hubhome")
                graph("hubhome") {
                    start(settings)
                    screens(settings)
                }
            }
            intercept(guard = adminGuard) {
                graph("admin") {
                    start(reports)
                    screens(reports)
                    intercept(guard = auditGuard) {
                        graph("audit") {
                            start(audit)
                            screens(audit)
                        }
                    }
                }
                screens(vip)
            }
        }
    }

    private val map: NavigationLinkMap by lazy { module().linkMap() }

    private fun route(path: String) = map.routes.single { it.path == path }

    private fun graph(id: String) = map.graphs.single { it.id == id }

    @Test
    fun `every path the app can open is listed with the screen it opens`() {
        assertEquals(
            listOf(
                "home", "settings", "user/{id}", "user/{uid}", "profile/{userId}", "sheet", "alert", "vip",
                "news/feed", "news/article/{slug}", "wizard/step", "wizard/nested/step", "dynamic/reports",
                "umbrella/inner/overview", "hub/hubhome/settings", "admin/reports", "admin/audit/audit",
                "missing", "crash", "loading"
            ),
            map.routes.map { it.path }
        )
        assertEquals("LinkProfileScreen", route("profile/{userId}").screen)
        assertNull(route("home").screen)
        assertEquals("article/{slug}", route("news/article/{slug}").route)
        assertEquals("news", route("news/article/{slug}").graph)
    }

    @Test
    fun `a screen registered in two graphs keeps both paths`() {
        assertEquals("wizard", route("wizard/step").graph)
        assertEquals("nested", route("wizard/nested/step").graph)
    }

    @Test
    fun `path params come from the template`() {
        assertEquals(listOf("slug"), route("news/article/{slug}").params)
        assertEquals(listOf("userId"), route("profile/{userId}").params)
        assertEquals(emptyList(), route("home").params)
    }

    @Test
    fun `modals are marked as modals`() {
        assertEquals(LinkKind.Modal, route("sheet").kind)
        assertEquals(LinkKind.Screen, route("home").kind)
    }

    @Test
    fun `access says whether a link can land on the route`() {
        assertEquals(LinkAccess.Linkable, route("home").access)
        assertEquals(LinkAccess.Linkable, route("user/{id}").access)
        assertEquals(LinkAccess.Shadowed, route("user/{uid}").access)
        assertEquals(LinkAccess.Internal, route("alert").access)
        assertEquals(LinkAccess.Internal, route("loading").access)
        assertEquals(LinkAccess.Internal, route("crash").access)
        assertEquals(LinkAccess.Fallback, route("missing").access)
    }

    @Test
    fun `guard chains are listed outermost first and shared guards share an id`() {
        assertEquals(listOf(1), graph("admin").guards)
        assertEquals(listOf(1, 2), graph("audit").guards)
        assertEquals(listOf(1, 2), route("admin/audit/audit").guards)
        assertEquals(listOf(1), route("vip").guards)
        assertEquals(emptyList(), route("home").guards)
    }

    @Test
    fun `graphs describe their place in the tree and how they start`() {
        assertEquals("", graph("root").path)
        assertNull(graph("root").parent)
        assertEquals(LinkAccess.Internal, graph("root").access)
        assertEquals(LinkStart.Route("home"), graph("root").start)
        assertEquals("root", graph("news").parent)
        assertEquals(LinkStart.Route("news/feed"), graph("news").start)
        assertEquals("wizard/nested", graph("nested").path)
        assertEquals("wizard", graph("nested").parent)
        assertEquals(LinkStart.Dynamic, graph("dynamic").start)
        assertEquals(LinkStart.None, graph("umbrella").start)
        assertEquals(LinkAccess.Internal, graph("umbrella").access)
        assertEquals(LinkStart.Graph("hubhome"), graph("hub").start)
        assertEquals("admin", graph("audit").parent)
    }

    @Test
    fun `aliases list their pattern target and params`() {
        assertEquals(
            listOf(LinkAlias("{scheme}://{host}/p/{userId}", "profile/{userId}", listOf("scheme", "host", "userId"))),
            map.aliases
        )
    }

    @Test
    fun `the web prefix follows the url style and is absent without a browser`() = runTest {
        assertNull(map.webPrefix)

        val pathBrowser = FakeBrowser(StandardTestDispatcher(testScheduler), FakeBrowser.ORIGIN + "/app/", basePath = "/app/")
        val pathMap = createNavigationModule {
            browserHistoryForTesting(pathBrowser)
            rootGraph {
                start(home)
                screens(home)
            }
        }.linkMap()
        assertEquals("/app/", pathMap.webPrefix)

        val hashMap = createNavigationModule {
            browserHistoryForTesting(fakeBrowser(), UrlStyle.Hash)
            rootGraph {
                start(home)
                screens(home)
            }
        }.linkMap()
        assertEquals("#/", hashMap.webPrefix)
    }

    @Test
    fun `the map survives a json round trip`() {
        val json = reaktivJson(encodeDefaults = true)

        val restored = json.decodeFromString(NavigationLinkMap.serializer(), json.encodeToString(NavigationLinkMap.serializer(), map))

        assertEquals(map, restored)
    }
}
