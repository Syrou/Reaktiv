package io.github.syrou.reaktiv.devtools.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import io.github.syrou.reaktiv.core.ExperimentalReaktivApi
import io.github.syrou.reaktiv.core.Store
import io.github.syrou.reaktiv.core.createStore
import io.github.syrou.reaktiv.core.util.selectState
import io.github.syrou.reaktiv.devtools.client.DevToolsConnection
import io.github.syrou.reaktiv.devtools.middleware.DevToolsConfig
import io.github.syrou.reaktiv.devtools.protocol.ClientRole
import io.github.syrou.reaktiv.devtools.server.DevToolsServer
import io.github.syrou.reaktiv.devtools.server.RunningDevToolsServer
import io.github.syrou.reaktiv.devtools.service.DevToolsService
import io.github.syrou.reaktiv.devtools.ui.navmap.MapLinkOutcome
import io.github.syrou.reaktiv.devtools.ui.navmap.NAVIGATION_LINKS_EXTENSION
import io.github.syrou.reaktiv.devtools.ui.navmap.deviceEntry
import io.github.syrou.reaktiv.devtools.ui.navmap.navigationSnapshot
import io.github.syrou.reaktiv.devtools.ui.navmap.parseAppLinkFiles
import io.github.syrou.reaktiv.devtools.ui.navmap.parseLinkMap
import io.github.syrou.reaktiv.devtools.ui.navmap.parseLinkOutcome
import io.github.syrou.reaktiv.introspection.IntrospectionConfig
import io.github.syrou.reaktiv.introspection.PlatformContext
import io.github.syrou.reaktiv.introspection.tooling.ToolingService
import io.github.syrou.reaktiv.introspection.tooling.ToolingServiceContext
import io.github.syrou.reaktiv.introspection.tooling.ToolingState
import io.github.syrou.reaktiv.introspection.tooling.createToolingModule
import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.createNavigationModule
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.model.GuardResult
import io.github.syrou.reaktiv.navigation.param.Params
import io.github.syrou.reaktiv.navigation.tooling.NavigationLinks
import io.github.syrou.reaktiv.navigation.transition.NavTransition
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@OptIn(ExperimentalReaktivApi::class)
class NavigationMapE2ETest {

    private lateinit var server: RunningDevToolsServer
    private var serverPort: Int = 0
    private val stores = mutableListOf<Store>()
    private val connections = mutableListOf<DevToolsConnection>()

    private fun screen(route: String) = object : Screen {
        override val route = route
        override val enterTransition = NavTransition.None
        override val exitTransition = NavTransition.None

        @Composable
        override fun Content(params: Params) {
            Text(route)
        }
    }

    private val home = screen("home")
    private val login = screen("login")
    private val feed = screen("feed")
    private val article = screen("article/{slug}")
    private val reports = screen("reports")

    @BeforeTest
    fun startServer() = runBlocking {
        server = DevToolsServer.startEmbedded(port = 0)
        serverPort = server.port()
    }

    @AfterTest
    fun stopServer() {
        connections.forEach { runCatching { runBlocking { it.disconnect() } } }
        stores.forEach { runCatching { it.cleanup() } }
        server.stop()
    }

    private class HoldingService(private val gate: CompletableDeferred<Unit>) : ToolingService {
        override val name: String = "holding"

        override suspend fun start(context: ToolingServiceContext) {
            gate.await()
        }

        override suspend fun stop() {}
    }

    private suspend fun publisher(
        allowRemoteRequests: Boolean = true,
        holdLinksUntil: CompletableDeferred<Unit>? = null
    ): Store {
        val store = createStore {
            module(
                createToolingModule(
                    IntrospectionConfig(
                        clientId = "phone",
                        clientName = "phone",
                        platform = "JVM",
                        installLogicTracing = false,
                        installStallWatchdog = false,
                        installCrashHandler = false
                    ),
                    PlatformContext()
                ) {
                    install(
                        DevToolsService(
                            DevToolsConfig(
                                serverUrl = "ws://127.0.0.1:$serverPort/ws",
                                autoReconnect = false,
                                defaultRole = ClientRole.PUBLISHER,
                                allowRemoteRequests = allowRemoteRequests
                            )
                        )
                    )
                    holdLinksUntil?.let { install(HoldingService(it)) }
                    install(NavigationLinks())
                }
            )
            module(
                createNavigationModule {
                    rootGraph {
                        start(home)
                        screens(home, login)
                        graph("news") {
                            start(feed)
                            screens(feed, article)
                        }
                        intercept(guard = { _ -> GuardResult.RedirectTo(login) }) {
                            graph("admin") {
                                start(reports)
                                screens(reports)
                            }
                        }
                    }
                }
            )
            coroutineContext(Dispatchers.Default)
        }.also(stores::add)
        val publishing = withTimeoutOrNull(20_000) {
            store.selectState<ToolingState>().first { it.services["devtools"]?.detail?.contains("publishing") == true }
        }
        assertNotNull(publishing, "the publisher never started publishing")
        return store
    }

    private suspend fun connectedUi(): Store {
        val store = createStore {
            module(DevToolsUiModule)
            coroutineContext(Dispatchers.Default)
        }.also(stores::add)
        val connection = DevToolsConnection("ws://127.0.0.1:$serverPort/ws").also(connections::add)
        DevToolsUiModule.selectLogicTyped(store).setConnection(connection)
        connection.connect(DEVTOOLS_UI_CLIENT_ID, "DevTools UI", "JVM")
        return store
    }

    private suspend fun ui(): Store {
        val store = connectedUi()
        val withMap = withTimeoutOrNull(20_000) {
            store.selectState<DevToolsUiState>().first { it.extensions.containsKey(NAVIGATION_LINKS_EXTENSION) }
        }
        assertNotNull(withMap, "the UI never received the link map")
        return store
    }

    private suspend fun Store.open(path: String, link: String = path, params: Map<String, String> = emptyMap()): LinkAttempt {
        DevToolsUiModule.selectLogicTyped(this).openLinkOnPublisher(path, link, params)
        val answered = withTimeoutOrNull(20_000) {
            selectState<DevToolsUiState>().first { state ->
                state.linkAttempts.lastOrNull()?.let { it.path == path && it.status != RequestStatus.PENDING } == true
            }
        }
        return assertNotNull(answered, "no answer for $path").linkAttempts.last()
    }

    @Test
    fun `the ui receives the map of every route the publisher can open`() = runBlocking<Unit> {
        publisher()
        val ui = ui()

        val map = assertNotNull(parseLinkMap(ui.selectState<DevToolsUiState>().first().extensions[NAVIGATION_LINKS_EXTENSION]))

        assertEquals(listOf("home", "login", "news/feed", "news/article/{slug}", "admin/reports"), map.routes.map { it.path })
    }

    @Test
    fun `a map published after the ui synced still reaches it`() = runBlocking<Unit> {
        val gate = CompletableDeferred<Unit>()
        try {
            publisher(holdLinksUntil = gate)
            val ui = connectedUi()
            val synced = withTimeoutOrNull(20_000) {
                ui.selectState<DevToolsUiState>().first { it.publisherSessionStart != null }
            }
            assertNull(assertNotNull(synced, "the UI never synced the publisher").extensions[NAVIGATION_LINKS_EXTENSION])

            gate.complete(Unit)

            val withMap = withTimeoutOrNull(20_000) {
                ui.selectState<DevToolsUiState>().first { it.extensions.containsKey(NAVIGATION_LINKS_EXTENSION) }
            }
            assertNotNull(withMap, "a map published after the first sync never reached the UI")
        } finally {
            gate.complete(Unit)
        }
    }

    @Test
    fun `opening a route on the device lands there and reports it`() = runBlocking<Unit> {
        val phone = publisher()
        val ui = ui()

        val attempt = ui.open("news/article/{slug}", params = mapOf("slug" to "kotlin"))

        assertEquals(MapLinkOutcome.Landed("news/article/kotlin"), parseLinkOutcome(attempt.result))
        val current = phone.selectState<NavigationState>().first { it.currentEntry.path == "news/article/{slug}" }.currentEntry
        assertEquals("kotlin", current.params.getString("slug"))
    }

    @Test
    fun `the ui reads the values the device's route was opened with`() = runBlocking<Unit> {
        publisher()
        val ui = ui()

        ui.open("news/article/{slug}", params = mapOf("slug" to "kotlin 2"))
        val showing = withTimeoutOrNull(20_000) {
            ui.selectState<DevToolsUiState>().first { it.navigationSnapshot()?.deviceEntry("news/article/{slug}")?.belowTop == 0 }
        }

        val device = assertNotNull(assertNotNull(showing, "the UI never saw the article showing").navigationSnapshot()?.deviceEntry("news/article/{slug}"))
        assertEquals(mapOf("slug" to "kotlin 2"), device.entry.params)
        assertEquals("news/article/kotlin%202", device.entry.location)
    }

    @Test
    fun `missing params and guard redirects come back as outcomes`() = runBlocking<Unit> {
        publisher()
        val ui = ui()

        val missing = ui.open("news/article/{slug}")
        val redirected = ui.open("admin/reports")

        assertEquals(MapLinkOutcome.MissingParams(listOf("slug")), parseLinkOutcome(missing.result))
        assertIs<MapLinkOutcome.Redirected>(parseLinkOutcome(redirected.result))
    }

    @Test
    fun `the ui asks the device for app links files for the chosen routes`() = runBlocking<Unit> {
        publisher()
        val ui = ui()
        val form = AppLinksForm(
            host = "example.com",
            appleAppIds = "ABCDE12345.com.example",
            selectedPaths = setOf("news/article/*"),
            deepLinkScheme = " myapp "
        )

        val returned = DevToolsUiModule.selectLogicTyped(ui).requestAppLinks(form)
        val answered = ui.selectState<DevToolsUiState>().first()

        assertEquals(RequestStatus.ANSWERED, answered.appLinksCall?.status)
        val files = assertNotNull(parseAppLinkFiles(answered.appLinksCall?.result))
        assertEquals(files, returned)
        assertEquals(listOf("/news/article/*"), files.paths.map { it.pattern })
        assertEquals(true, files.candidates.size > files.paths.size)
        assertEquals(true, files.appleAppSiteAssociation?.contains("ABCDE12345.com.example"))
        assertEquals(true, files.androidDeepLinkIntentFilter?.contains("<data android:scheme=\"myapp\" />"))
        assertEquals(true, files.androidDeepLinkIntentFilter?.contains("<data android:pathPattern=\"/news/article/.*\" />"))
    }

    @Test
    fun `a device that turned remote requests off answers with the reason`() = runBlocking<Unit> {
        val phone = publisher(allowRemoteRequests = false)
        val ui = ui()

        val attempt = ui.open("news/feed")

        assertEquals("Remote requests are turned off on this device", attempt.error)
        assertEquals("home", phone.selectState<NavigationState>().first().currentEntry.path)
    }
}
