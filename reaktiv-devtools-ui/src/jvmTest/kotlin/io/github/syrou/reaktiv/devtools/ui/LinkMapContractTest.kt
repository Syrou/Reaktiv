package io.github.syrou.reaktiv.devtools.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import io.github.syrou.reaktiv.core.util.reaktivJson
import io.github.syrou.reaktiv.devtools.ui.navmap.APP_LINKS_REQUEST
import io.github.syrou.reaktiv.devtools.ui.navmap.MapAccess
import io.github.syrou.reaktiv.devtools.ui.navmap.appLinksPayload
import io.github.syrou.reaktiv.devtools.ui.navmap.downloads
import io.github.syrou.reaktiv.devtools.ui.navmap.parseAppLinkFiles
import io.github.syrou.reaktiv.navigation.link.AppLinkFiles
import io.github.syrou.reaktiv.navigation.link.AppLinksConfig
import io.github.syrou.reaktiv.navigation.link.appLinkFiles
import io.github.syrou.reaktiv.devtools.ui.navmap.MapKind
import io.github.syrou.reaktiv.devtools.ui.navmap.MapLinkOutcome
import io.github.syrou.reaktiv.devtools.ui.navmap.MapModel
import io.github.syrou.reaktiv.devtools.ui.navmap.MapStart
import io.github.syrou.reaktiv.devtools.ui.navmap.NAVIGATION_LINKS_EXTENSION
import io.github.syrou.reaktiv.devtools.ui.navmap.NAVIGATION_LINKS_SERVICE
import io.github.syrou.reaktiv.devtools.ui.navmap.OPEN_LINK_REQUEST
import io.github.syrou.reaktiv.devtools.ui.navmap.openLinkPayload
import io.github.syrou.reaktiv.devtools.ui.navmap.parseLinkMap
import io.github.syrou.reaktiv.devtools.ui.navmap.parseLinkOutcome
import io.github.syrou.reaktiv.navigation.createNavigationModule
import io.github.syrou.reaktiv.navigation.definition.Modal
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.link.LinkAccess
import io.github.syrou.reaktiv.navigation.link.LinkKind
import io.github.syrou.reaktiv.navigation.link.LinkOutcome
import io.github.syrou.reaktiv.navigation.link.LinkStart
import io.github.syrou.reaktiv.navigation.link.NavigationLinkMap
import io.github.syrou.reaktiv.navigation.model.GuardResult
import io.github.syrou.reaktiv.navigation.param.Params
import io.github.syrou.reaktiv.navigation.tooling.NavigationLinks
import io.github.syrou.reaktiv.navigation.tooling.OpenLinkPayload
import io.github.syrou.reaktiv.navigation.transition.NavTransition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class LinkMapContractTest {

    private fun screen(route: String) = object : Screen {
        override val route = route
        override val enterTransition = NavTransition.None
        override val exitTransition = NavTransition.None

        @Composable
        override fun Content(params: Params) {
            Text(route)
        }
    }

    private fun modal(route: String) = object : Modal {
        override val route = route
        override val enterTransition = NavTransition.None
        override val exitTransition = NavTransition.None

        @Composable
        override fun Content(params: Params) {
            Text(route)
        }
    }

    private val home = screen("home")
    private val article = screen("article/{slug}")
    private val reports = screen("reports")
    private val missing = screen("missing")

    private val navigationMap: NavigationLinkMap = createNavigationModule {
        notFoundScreen(missing)
        deepLinkAliases {
            alias("{scheme}://{host}/a/{slug}", "news/article/{slug}")
        }
        rootGraph {
            start(home)
            screens(home)
            modals(modal("sheet"))
            graph("news") {
                start(article)
                screens(article)
            }
            graph("dynamic") {
                start(route = { _ -> reports })
                screens(reports)
            }
            intercept(guard = { _ -> GuardResult.Allow }) {
                graph("admin") {
                    start(reports)
                    screens(reports)
                }
            }
        }
    }.linkMap()

    private fun MapStart.matches(start: LinkStart): Boolean = when (start) {
        is LinkStart.Route -> this == MapStart.Route(start.path)
        is LinkStart.Graph -> this == MapStart.Graph(start.id)
        LinkStart.Dynamic -> this == MapStart.Dynamic
        LinkStart.None -> this == MapStart.None
    }

    private fun assertReadsBack(model: MapModel) {
        assertEquals(navigationMap.webPrefix, model.webPrefix)
        assertEquals(navigationMap.graphs.size, model.graphs.size)
        navigationMap.graphs.zip(model.graphs).forEach { (expected, actual) ->
            assertEquals(expected.id, actual.id)
            assertEquals(expected.path, actual.path)
            assertEquals(expected.parent, actual.parent)
            assertEquals(expected.guards, actual.guards)
            assertEquals(expected.access.name, actual.access.name)
            assertEquals(true, actual.start.matches(expected.start), "start of ${expected.id}")
        }
        assertEquals(navigationMap.routes.size, model.routes.size)
        navigationMap.routes.zip(model.routes).forEach { (expected, actual) ->
            assertEquals(expected.path, actual.path)
            assertEquals(expected.route, actual.route)
            assertEquals(expected.graph, actual.graph)
            assertEquals(expected.screen, actual.screen)
            assertEquals(expected.kind.name, actual.kind.name)
            assertEquals(expected.params, actual.params)
            assertEquals(expected.guards, actual.guards)
            assertEquals(expected.access.name, actual.access.name)
        }
        assertEquals(navigationMap.aliases.map { Triple(it.pattern, it.target, it.params) }, model.aliases.map { Triple(it.pattern, it.target, it.params) })
    }

    @Test
    fun `the lens reads every field of the map the plugin publishes`() {
        val published = reaktivJson(encodeDefaults = true).encodeToJsonElement(NavigationLinkMap.serializer(), navigationMap)

        assertReadsBack(assertNotNull(parseLinkMap(published)))
    }

    @Test
    fun `the lens reads a map encoded without defaults as sent on the wire`() {
        val wire = reaktivJson().encodeToJsonElement(NavigationLinkMap.serializer(), navigationMap)

        assertReadsBack(assertNotNull(parseLinkMap(wire)))
    }

    @Test
    fun `the fixture exercises every kind the lens has to read`() {
        assertEquals(setOf(LinkKind.Screen, LinkKind.Modal), navigationMap.routes.map { it.kind }.toSet())
        assertEquals(true, navigationMap.routes.any { it.access == LinkAccess.Fallback })
        assertEquals(true, navigationMap.graphs.any { it.start == LinkStart.Dynamic })
        assertEquals(true, navigationMap.graphs.any { it.guards.isNotEmpty() })
        assertEquals(MapKind.Modal, parseLinkMap(reaktivJson().encodeToJsonElement(NavigationLinkMap.serializer(), navigationMap))!!.routes.first { it.path == "sheet" }.kind)
        assertEquals(MapAccess.Fallback, parseLinkMap(reaktivJson().encodeToJsonElement(NavigationLinkMap.serializer(), navigationMap))!!.routes.first { it.path == "missing" }.access)
    }

    @Test
    fun `the lens reads every outcome the plugin answers with`() {
        val json = reaktivJson(encodeDefaults = true)
        val outcomes = listOf(
            LinkOutcome.Landed("news/article/kotlin") to MapLinkOutcome.Landed("news/article/kotlin"),
            LinkOutcome.Redirected("login") to MapLinkOutcome.Redirected("login"),
            LinkOutcome.Rejected to MapLinkOutcome.Rejected,
            LinkOutcome.NotFound("nope") to MapLinkOutcome.NotFound("nope"),
            LinkOutcome.MissingParams(listOf("slug")) to MapLinkOutcome.MissingParams(listOf("slug")),
            LinkOutcome.Ignored("off") to MapLinkOutcome.Ignored("off")
        )

        outcomes.forEach { (outcome, expected) ->
            assertEquals(expected, parseLinkOutcome(json.encodeToJsonElement(LinkOutcome.serializer(), outcome)))
        }
    }

    @Test
    fun `the app links request and reply read the same on both sides`() {
        val payload = appLinksPayload(
            host = "example.com",
            basePath = "/app",
            appleAppIds = listOf("ABCDE12345.com.example"),
            androidPackage = "com.example",
            androidCertFingerprints = listOf("AA:BB"),
            androidDynamicPaths = false,
            paths = setOf("news/article/*"),
            deepLinkScheme = "myapp",
            deepLinkHost = "open"
        )
        val config = reaktivJson().decodeFromJsonElement(AppLinksConfig.serializer(), payload)
        assertEquals(
            AppLinksConfig(
                host = "example.com",
                basePath = "/app",
                appleAppIds = listOf("ABCDE12345.com.example"),
                androidPackage = "com.example",
                androidCertFingerprints = listOf("AA:BB"),
                androidDynamicPaths = false,
                paths = setOf("news/article/*"),
                deepLinkScheme = "myapp",
                deepLinkHost = "open"
            ),
            config
        )

        val files = navigationMap.appLinkFiles(config)
        val model = assertNotNull(parseAppLinkFiles(reaktivJson(encodeDefaults = true).encodeToJsonElement(AppLinkFiles.serializer(), files)))
        assertEquals(files.candidates.map { it.path to it.pattern }, model.candidates.map { it.path to it.pattern })
        assertEquals(files.paths.map { it.pattern }, model.paths.map { it.pattern })
        assertEquals(files.appleAppSiteAssociation, model.appleAppSiteAssociation)
        assertEquals(files.assetLinks, model.assetLinks)
        assertEquals(files.androidManifestIntentFilter, model.androidManifestIntentFilter)
        assertNotNull(files.androidDeepLinkIntentFilter)
        assertEquals(files.androidDeepLinkIntentFilter, model.androidDeepLinkIntentFilter)
        assertEquals(NavigationLinks.APP_LINKS_REQUEST, APP_LINKS_REQUEST)
    }

    @Test
    fun `generate downloads every file the device generated under its own name`() {
        val encoder = reaktivJson(encodeDefaults = true)
        val everything = navigationMap.appLinkFiles(
            AppLinksConfig(
                host = "example.com",
                appleAppIds = listOf("ABCDE12345.com.example"),
                androidPackage = "com.example",
                deepLinkScheme = "myapp"
            )
        )
        val model = assertNotNull(parseAppLinkFiles(encoder.encodeToJsonElement(AppLinkFiles.serializer(), everything)))

        assertEquals(
            listOf(
                "apple-app-site-association" to everything.appleAppSiteAssociation,
                "assetlinks.json" to everything.assetLinks,
                "AndroidManifest-app-links.xml" to everything.androidManifestIntentFilter,
                "AndroidManifest-deep-links.xml" to everything.androidDeepLinkIntentFilter
            ),
            model.downloads()
        )

        val manifestOnly = navigationMap.appLinkFiles(AppLinksConfig(host = "example.com"))
        val onlyModel = assertNotNull(parseAppLinkFiles(encoder.encodeToJsonElement(AppLinkFiles.serializer(), manifestOnly)))
        assertEquals(listOf("AndroidManifest-app-links.xml"), onlyModel.downloads().map { it.first })
    }

    @Test
    fun `selecting no routes reaches the device as none rather than all`() {
        val none = reaktivJson().decodeFromJsonElement(
            AppLinksConfig.serializer(),
            appLinksPayload("example.com", null, listOf("ABCDE12345.com.example"), null, emptyList(), true, emptySet())
        )
        val all = reaktivJson().decodeFromJsonElement(
            AppLinksConfig.serializer(),
            appLinksPayload("example.com", null, listOf("ABCDE12345.com.example"), null, emptyList(), true, null)
        )

        assertEquals(emptySet(), none.paths)
        assertEquals(emptyList(), navigationMap.appLinkFiles(none).paths)
        assertEquals(navigationMap.appLinkFiles(all).candidates, navigationMap.appLinkFiles(all).paths)
    }

    @Test
    fun `the open request the UI builds is the one the plugin reads`() {
        val payload = openLinkPayload("news/article/{slug}", mapOf("slug" to "kotlin"))

        assertEquals(
            OpenLinkPayload("news/article/{slug}", mapOf("slug" to "kotlin")),
            reaktivJson().decodeFromJsonElement(OpenLinkPayload.serializer(), payload)
        )
        assertEquals(NavigationLinks.SERVICE_NAME, NAVIGATION_LINKS_SERVICE)
        assertEquals(NavigationLinks.EXTENSION_KEY, NAVIGATION_LINKS_EXTENSION)
        assertEquals(NavigationLinks.OPEN_REQUEST, OPEN_LINK_REQUEST)
    }
}
