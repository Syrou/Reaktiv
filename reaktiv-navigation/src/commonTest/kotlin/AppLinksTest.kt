import io.github.syrou.reaktiv.navigation.createNavigationModule
import io.github.syrou.reaktiv.navigation.link.AppLinkPath
import io.github.syrou.reaktiv.navigation.link.AppLinksConfig
import io.github.syrou.reaktiv.navigation.link.LinkAccess
import io.github.syrou.reaktiv.navigation.link.LinkAlias
import io.github.syrou.reaktiv.navigation.link.LinkGraph
import io.github.syrou.reaktiv.navigation.link.LinkKind
import io.github.syrou.reaktiv.navigation.link.LinkRoute
import io.github.syrou.reaktiv.navigation.link.LinkStart
import io.github.syrou.reaktiv.navigation.link.NavigationLinkMap
import io.github.syrou.reaktiv.navigation.link.appLinkFiles
import io.github.syrou.reaktiv.navigation.link.appLinkPaths
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppLinksTest {

    private val map = NavigationLinkMap(
        graphs = listOf(
            LinkGraph("root", "", null, LinkStart.Route("home"), access = LinkAccess.Internal),
            LinkGraph("leaderboard", "leaderboard", "root", LinkStart.Route("leaderboard/overview")),
            LinkGraph("umbrella", "umbrella", "root", LinkStart.None, access = LinkAccess.Internal)
        ),
        routes = listOf(
            LinkRoute("home", "home", "root", "HomeScreen"),
            LinkRoute("leaderboard/overview", "overview", "leaderboard", "LeaderboardScreen"),
            LinkRoute("leaderboard/player/{playerId}", "player/{playerId}", "leaderboard", "PlayerProfileScreen", params = listOf("playerId")),
            LinkRoute("files/item-{id}.json", "item-{id}.json", "root", "FileScreen", params = listOf("id")),
            LinkRoute("sheet", "sheet", "root", kind = LinkKind.Modal),
            LinkRoute("loading", "loading", "root", access = LinkAccess.Internal),
            LinkRoute("missing", "missing", "root", access = LinkAccess.Fallback),
            LinkRoute("user/{uid}", "user/{uid}", "root", params = listOf("uid"), access = LinkAccess.Shadowed)
        ),
        aliases = listOf(
            LinkAlias("{scheme}://{host}/p/{playerId}", "leaderboard/player/{playerId}", listOf("scheme", "host", "playerId")),
            LinkAlias("https://example.com/invite/{code}", "home", listOf("code")),
            LinkAlias("myapp://open/{screen}", "home", listOf("screen")),
            LinkAlias("promo/{campaign}", "home", listOf("campaign"))
        ),
        webPrefix = "/app/"
    )

    @Test
    fun `every linkable route graph and web alias becomes a path under the web base`() {
        assertEquals(
            listOf(
                AppLinkPath("home", "/app/home", "home", "HomeScreen"),
                AppLinkPath("leaderboard/overview", "/app/leaderboard/overview", "leaderboard/overview", "LeaderboardScreen"),
                AppLinkPath("leaderboard/player/*", "/app/leaderboard/player/*", "leaderboard/player/{playerId}", "PlayerProfileScreen", listOf("playerId")),
                AppLinkPath("files/item-*.json", "/app/files/item-*.json", "files/item-{id}.json", "FileScreen", listOf("id")),
                AppLinkPath("sheet", "/app/sheet", "sheet", null),
                AppLinkPath("leaderboard", "/app/leaderboard", "leaderboard", null),
                AppLinkPath("p/*", "/app/p/*", "leaderboard/player/{playerId}", "PlayerProfileScreen", listOf("scheme", "host", "playerId")),
                AppLinkPath("invite/*", "/app/invite/*", "home", "HomeScreen", listOf("code")),
                AppLinkPath("promo/*", "/app/promo/*", "home", "HomeScreen", listOf("campaign"))
            ),
            map.appLinkPaths()
        )
    }

    @Test
    fun `routes a link cannot reach and custom scheme aliases are left out`() {
        val patterns = map.appLinkPaths().map { it.pattern }

        assertFalse(patterns.any { "loading" in it || "missing" in it || "user" in it || "umbrella" in it })
        assertFalse(patterns.any { "open" in it })
    }

    @Test
    fun `the base can be given explicitly`() {
        assertEquals("/home", map.appLinkPaths("/").first().pattern)
        assertEquals("/shop/home", map.appLinkPaths("shop").first().pattern)
    }

    @Test
    fun `the apple app site association lists every path for every app id`() {
        val files = map.appLinkFiles(AppLinksConfig(host = "example.com", appleAppIds = listOf("ABCDE12345.com.example.app")))

        val details = Json.parseToJsonElement(files.appleAppSiteAssociation!!).jsonObject["applinks"]!!.jsonObject["details"]!!.jsonArray.single().jsonObject
        assertEquals("ABCDE12345.com.example.app", details["appIDs"]!!.jsonArray.single().jsonPrimitive.content)
        val components = details["components"]!!.jsonArray.map { it.jsonObject }
        assertEquals(files.paths.map { it.pattern }, components.map { it["/"]!!.jsonPrimitive.content })
        assertEquals("PlayerProfileScreen", components[2]["comment"]!!.jsonPrimitive.content)
    }

    @Test
    fun `asset links carry the package fingerprints and the android 15 path list`() {
        val files = map.appLinkFiles(
            AppLinksConfig(
                host = "example.com",
                androidPackage = "com.example.app",
                androidCertFingerprints = listOf("AA:BB")
            )
        )

        val statement = Json.parseToJsonElement(files.assetLinks!!).jsonArray.single().jsonObject
        assertEquals("delegate_permission/common.handle_all_urls", statement["relation"]!!.jsonArray.single().jsonPrimitive.content)
        val target = statement["target"]!!.jsonObject
        assertEquals("android_app", target["namespace"]!!.jsonPrimitive.content)
        assertEquals("com.example.app", target["package_name"]!!.jsonPrimitive.content)
        assertEquals("AA:BB", target["sha256_cert_fingerprints"]!!.jsonArray.single().jsonPrimitive.content)
        val dynamic = statement["relation_extensions"]!!.jsonObject["delegate_permission/common.handle_all_urls"]!!
            .jsonObject["dynamic_app_link_components"]!!.jsonArray
        assertEquals(files.paths.map { it.pattern }, dynamic.map { it.jsonObject["/"]!!.jsonPrimitive.content })
    }

    @Test
    fun `asset links can leave the path list out`() {
        val files = map.appLinkFiles(
            AppLinksConfig(host = "example.com", androidPackage = "com.example.app", androidDynamicPaths = false)
        )

        val statement = Json.parseToJsonElement(files.assetLinks!!).jsonArray.single().jsonObject
        assertFalse("relation_extensions" in statement)
    }

    @Test
    fun `the manifest filter verifies the host and matches every path`() {
        val filter = map.appLinkFiles(AppLinksConfig(host = "example.com")).androidManifestIntentFilter

        assertTrue("android:autoVerify=\"true\"" in filter)
        assertTrue("<data android:host=\"example.com\" />" in filter)
        assertTrue("<data android:path=\"/app/home\" />" in filter)
        assertTrue("<data android:pathPattern=\"/app/leaderboard/player/.*\" />" in filter)
        assertTrue("<data android:pathPattern=\"/app/files/item-.*\\\\.json\" />" in filter)
    }

    @Test
    fun `only the chosen paths are exported while every candidate stays listed`() {
        val files = map.appLinkFiles(
            AppLinksConfig(
                host = "example.com",
                appleAppIds = listOf("ABCDE12345.com.example.app"),
                paths = setOf("leaderboard/player/*", "promo/*")
            )
        )

        assertEquals(listOf("/app/leaderboard/player/*", "/app/promo/*"), files.paths.map { it.pattern })
        assertEquals(9, files.candidates.size)
        val components = Json.parseToJsonElement(files.appleAppSiteAssociation!!).jsonObject["applinks"]!!.jsonObject["details"]!!
            .jsonArray.single().jsonObject["components"]!!.jsonArray
        assertEquals(2, components.size)
        assertFalse("/app/home" in files.androidManifestIntentFilter)
    }

    @Test
    fun `files that were not configured are not generated`() {
        val files = map.appLinkFiles(AppLinksConfig(host = "example.com"))

        assertNull(files.appleAppSiteAssociation)
        assertNull(files.assetLinks)
    }

    @Test
    fun `a map read from a real module produces paths for its linkable routes`() {
        val home = historyScreen("home")
        val user = historyScreen("user/{id}")
        val module = createNavigationModule {
            rootGraph {
                start(home)
                screens(home, user)
            }
        }

        assertEquals(listOf("/home", "/user/*"), module.linkMap().appLinkPaths().map { it.pattern })
    }
}
