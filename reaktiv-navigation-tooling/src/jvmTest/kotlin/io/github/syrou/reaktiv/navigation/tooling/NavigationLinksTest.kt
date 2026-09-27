package io.github.syrou.reaktiv.navigation.tooling

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import io.github.syrou.reaktiv.core.Store
import io.github.syrou.reaktiv.core.createStore
import io.github.syrou.reaktiv.core.util.reaktivJson
import io.github.syrou.reaktiv.core.util.selectLogic
import io.github.syrou.reaktiv.introspection.IntrospectionConfig
import io.github.syrou.reaktiv.introspection.PlatformContext
import io.github.syrou.reaktiv.introspection.tooling.ServiceState
import io.github.syrou.reaktiv.introspection.tooling.ToolingLogic
import io.github.syrou.reaktiv.introspection.tooling.ToolingState
import io.github.syrou.reaktiv.introspection.tooling.createToolingModule
import io.github.syrou.reaktiv.navigation.NavigationModule
import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.createNavigationModule
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.link.AppLinkFiles
import io.github.syrou.reaktiv.navigation.link.AppLinksConfig
import io.github.syrou.reaktiv.navigation.link.LinkOutcome
import io.github.syrou.reaktiv.navigation.link.NavigationLinkMap
import io.github.syrou.reaktiv.navigation.param.Params
import io.github.syrou.reaktiv.navigation.transition.NavTransition
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class NavigationLinksTest {

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
    private val article = screen("article/{slug}")
    private val json = reaktivJson(encodeDefaults = true)

    private fun navigation(): NavigationModule = createNavigationModule {
        rootGraph {
            start(home)
            screens(home)
            graph("news") {
                start(article)
                screens(article)
            }
        }
    }

    private fun config() = IntrospectionConfig(
        clientId = "links-test",
        clientName = "LinksTest",
        platform = "JVM",
        installCrashHandler = false,
        installStallWatchdog = false
    )

    private fun TestScope.store(links: NavigationLinks, navigation: NavigationModule? = navigation()): Store = createStore {
        module(createToolingModule(config(), PlatformContext()) { install(links) })
        navigation?.let { module(it) }
        coroutineContext(StandardTestDispatcher(testScheduler))
    }

    private suspend fun Store.open(payload: OpenLinkPayload): JsonElement? =
        selectLogic<ToolingLogic>().service(NavigationLinks.SERVICE_NAME)!!
            .onRequest(NavigationLinks.OPEN_REQUEST, json.encodeToJsonElement(OpenLinkPayload.serializer(), payload))

    @Test
    fun `the link map is published as a session extension`() = runTest {
        val navigation = navigation()
        val store = store(NavigationLinks(), navigation)
        advanceUntilIdle()

        val history = store.selectLogic<ToolingLogic>().getSessionCapture().getSessionHistory()
        val published = assertNotNull(history.extensions[NavigationLinks.EXTENSION_KEY])

        assertEquals(navigation.linkMap(), json.decodeFromJsonElement(NavigationLinkMap.serializer(), published))
        assertEquals(ServiceState.RUNNING, store.selectState<ToolingState>().first().services[NavigationLinks.SERVICE_NAME]?.state)
    }

    @Test
    fun `an open request opens the link and answers with the outcome`() = runTest {
        val store = store(NavigationLinks())
        advanceUntilIdle()

        val reply = store.open(OpenLinkPayload("news/article/{slug}", mapOf("slug" to "kotlin")))
        advanceUntilIdle()

        assertEquals(LinkOutcome.Landed("news/article/kotlin"), json.decodeFromJsonElement(LinkOutcome.serializer(), reply!!))
        assertEquals("news/article/{slug}", store.selectState<NavigationState>().first().currentEntry.path)
    }

    @Test
    fun `opening can be turned off on the device`() = runTest {
        val store = store(NavigationLinks(allowOpening = false))
        advanceUntilIdle()

        val reply = store.open(OpenLinkPayload("news/article/kotlin"))

        assertEquals(
            LinkOutcome.Ignored("Opening links is turned off on this device"),
            json.decodeFromJsonElement(LinkOutcome.serializer(), reply!!)
        )
        assertEquals("home", store.selectState<NavigationState>().first().currentEntry.path)
    }

    @Test
    fun `an app links request answers with the generated files`() = runTest {
        val store = store(NavigationLinks())
        advanceUntilIdle()
        val config = AppLinksConfig(host = "example.com", appleAppIds = listOf("ABCDE12345.com.example"), paths = setOf("news/article/*"))

        val reply = store.selectLogic<ToolingLogic>().service(NavigationLinks.SERVICE_NAME)!!
            .onRequest(NavigationLinks.APP_LINKS_REQUEST, json.encodeToJsonElement(AppLinksConfig.serializer(), config))
        val files = json.decodeFromJsonElement(AppLinkFiles.serializer(), reply!!)

        assertEquals(listOf("/home", "/news/article/*", "/news"), files.candidates.map { it.pattern })
        assertEquals(listOf("/news/article/*"), files.paths.map { it.pattern })
        assertNotNull(files.appleAppSiteAssociation)
        assertNull(files.assetLinks)
    }

    @Test
    fun `a request the service does not know is left unanswered`() = runTest {
        val store = store(NavigationLinks())
        advanceUntilIdle()

        val reply = store.selectLogic<ToolingLogic>().service(NavigationLinks.SERVICE_NAME)!!.onRequest("dance", JsonNull)

        assertNull(reply)
    }

    @Test
    fun `a store without navigation says why there is no map`() = runTest {
        val store = store(NavigationLinks(), navigation = null)
        advanceUntilIdle()

        val status = store.selectState<ToolingState>().first().services[NavigationLinks.SERVICE_NAME]
        assertEquals(ServiceState.DEGRADED, status?.state)
        assertEquals(emptyMap(), store.selectLogic<ToolingLogic>().getSessionCapture().getSessionHistory().extensions)
    }
}
