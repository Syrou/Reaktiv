import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import io.github.syrou.reaktiv.core.Store
import io.github.syrou.reaktiv.core.createStore
import io.github.syrou.reaktiv.core.util.selectState
import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.createNavigationModule
import io.github.syrou.reaktiv.navigation.definition.Graph
import io.github.syrou.reaktiv.navigation.definition.LoadingModal
import io.github.syrou.reaktiv.navigation.definition.NavigationPath
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.dsl.NavigationGraphBuilder
import io.github.syrou.reaktiv.navigation.extension.navigateDeepLink
import io.github.syrou.reaktiv.navigation.model.GuardResult
import io.github.syrou.reaktiv.navigation.param.Params
import io.github.syrou.reaktiv.navigation.transition.NavTransition
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.DurationUnit
import kotlin.time.toDuration

@OptIn(ExperimentalCoroutinesApi::class)
class TabHostDeepLinkTest {

    private fun screen(route: String) = object : Screen {
        override val route = route
        override val enterTransition = NavTransition.None
        override val exitTransition = NavTransition.None

        @Composable
        override fun Content(params: Params) { Text(route) }
    }

    private val loadingScreen = object : LoadingModal {
        override val route = "loading"
        override val enterTransition = NavTransition.None
        override val exitTransition = NavTransition.None

        @Composable
        override fun Content(params: Params) { Text("loading") }
    }

    private object TabHome : Graph {
        override val route = "home"
        override val startAnchorsChildren = false
    }

    private val signUp = screen("sign-up")
    private val releaseOverview = screen("release-overview")
    private val artistOverview = screen("artist-overview")
    private val artistEdit = screen("edit")
    private val step1 = screen("step1")
    private val step2 = screen("step2")

    private fun NavigationGraphBuilder.homeContents() {
        start(route = { _ -> NavigationPath("home/releases/release-overview") })
        graph("releases") {
            start(releaseOverview)
            screens(releaseOverview)
        }
        graph("artist") {
            start(artistOverview)
            screens(artistOverview, artistEdit)
        }
        graph("wizard") {
            start(step1)
            screens(step1, step2)
        }
    }

    private fun module(hostDeclaresPeers: Boolean) = createNavigationModule {
        loadingModal(loadingScreen)
        rootGraph {
            start(route = { _ -> NavigationPath("home") })
            graph("onboarding") {
                screens(signUp)
            }
            if (hostDeclaresPeers) {
                graph(TabHome) { homeContents() }
            } else {
                graph("home") { homeContents() }
            }
        }
    }

    private val awaitingData = screen("insight-awaiting-data")
    private val hubMain = screen("hub-main")

    private fun guardedInsightModule(redirectRoute: String) = createNavigationModule {
        loadingModal(loadingScreen)
        rootGraph {
            start(route = { _ -> NavigationPath("home") })
            intercept(guard = { _ -> GuardResult.Allow }) {
                graph(TabHome) {
                    start(route = { _ -> NavigationPath("home/releases/release-overview") })
                    graph("releases") {
                        start(releaseOverview)
                        screens(releaseOverview)
                    }
                    intercept(guard = { _ -> GuardResult.RedirectTo(redirectRoute) }) {
                        graph("insight") {
                            screens(awaitingData)
                            graph("insight-hub") {
                                start(hubMain)
                                screens(hubMain)
                            }
                        }
                    }
                }
            }
        }
        deepLinkAliases {
            alias("studio/streams", "home/insight") { Params.empty() }
        }
    }

    private fun deepLinkPathsWith(module: io.github.syrou.reaktiv.navigation.NavigationModule, link: String, assertion: (List<String>) -> Unit) =
        runTest(timeout = 20.toDuration(DurationUnit.SECONDS)) {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val store: Store = createStore {
                module(module)
                coroutineContext(dispatcher)
            }
            advanceUntilIdle()
            store.navigateDeepLink(link)
            advanceUntilIdle()
            assertion(store.selectState<NavigationState>().first().backStack.map { it.path })
        }

    @Test
    fun guardRedirectToBareRouteInsidePeerHostLandsAlone() =
        deepLinkPathsWith(guardedInsightModule("insight-awaiting-data"), "studio/streams") { paths ->
            assertEquals(listOf("home/insight/insight-awaiting-data"), paths)
        }

    @Test
    fun guardRedirectToFullPathInsidePeerHostLandsAlone() =
        deepLinkPathsWith(guardedInsightModule("home/insight/insight-awaiting-data"), "studio/streams") { paths ->
            assertEquals(listOf("home/insight/insight-awaiting-data"), paths)
        }

    private fun deepLinkPaths(hostDeclaresPeers: Boolean, link: String, assertion: (List<String>) -> Unit) =
        runTest(timeout = 20.toDuration(DurationUnit.SECONDS)) {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val store: Store = createStore {
                module(module(hostDeclaresPeers))
                coroutineContext(dispatcher)
            }
            advanceUntilIdle()
            store.navigateDeepLink(link)
            advanceUntilIdle()
            assertion(store.selectState<NavigationState>().first().backStack.map { it.path })
        }

    @Test
    fun deepLinkToTabLandsOnTheTabAlone() =
        deepLinkPaths(hostDeclaresPeers = true, link = "home/artist") { paths ->
            assertEquals(listOf("home/artist/artist-overview"), paths)
        }

    @Test
    fun deepLinkInsideTabKeepsTheTabsOwnStartBeneath() =
        deepLinkPaths(hostDeclaresPeers = true, link = "home/artist/edit") { paths ->
            assertEquals(listOf("home/artist/artist-overview", "home/artist/edit"), paths)
        }

    @Test
    fun deepLinkIntoFlowInsideTabKeepsTheFlowsStartBeneath() =
        deepLinkPaths(hostDeclaresPeers = true, link = "home/wizard/step2") { paths ->
            assertEquals(listOf("home/wizard/step1", "home/wizard/step2"), paths)
        }

    @Test
    fun deepLinkOutsideTheHostStillAnchorsTheAppsLanding() =
        deepLinkPaths(hostDeclaresPeers = true, link = "onboarding/sign-up") { paths ->
            assertEquals(listOf("home/releases/release-overview", "onboarding/sign-up"), paths)
        }

    @Test
    fun graphWithoutTheDeclarationAnchorsItsStartAsBefore() =
        deepLinkPaths(hostDeclaresPeers = false, link = "home/artist/edit") { paths ->
            assertEquals(
                listOf("home/releases/release-overview", "home/artist/artist-overview", "home/artist/edit"),
                paths
            )
        }
}
