import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import io.github.syrou.reaktiv.core.createStore
import io.github.syrou.reaktiv.core.util.selectState
import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.createNavigationModule
import io.github.syrou.reaktiv.navigation.definition.Graph
import io.github.syrou.reaktiv.navigation.definition.LoadingModal
import io.github.syrou.reaktiv.navigation.definition.NavigationPath
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.extension.navigateDeepLink
import io.github.syrou.reaktiv.navigation.model.CacheKeySelector
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
class WarmDeepLinkRootStartTest {

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

    private val releaseOverview = screen("release-overview")
    private val insightHub = screen("insight-hub")

    private var rootStarts = 0

    private fun module(rootCacheKey: CacheKeySelector?) = createNavigationModule {
        loadingModal(loadingScreen)
        rootGraph {
            start(route = { _ -> rootStarts++; NavigationPath("home") }, cacheKey = rootCacheKey)
            graph(TabHome) {
                start(route = { _ -> NavigationPath("home/releases/release-overview") })
                graph("releases") {
                    start(releaseOverview)
                    screens(releaseOverview)
                }
                graph("insight") {
                    start(insightHub)
                    screens(insightHub)
                }
            }
        }
    }

    private fun warmDeepLink(rootCacheKey: CacheKeySelector?, expectedRootStarts: Int) =
        runTest(timeout = 20.toDuration(DurationUnit.SECONDS)) {
            rootStarts = 0
            val dispatcher = StandardTestDispatcher(testScheduler)
            val store = createStore {
                module(module(rootCacheKey))
                coroutineContext(dispatcher)
            }
            advanceUntilIdle()
            assertEquals(1, rootStarts, "bootstrap evaluates the root start once")

            store.navigateDeepLink("home/insight")
            advanceUntilIdle()

            val paths = store.selectState<NavigationState>().first().backStack.map { it.path }
            assertEquals(listOf("home/insight/insight-hub"), paths)
            assertEquals(expectedRootStarts, rootStarts)
        }

    @Test
    fun withoutACacheKeyTheRootStartRunsAgainOnAWarmDeepLink() =
        warmDeepLink(rootCacheKey = null, expectedRootStarts = 2)

    @Test
    fun withAnUnchangedCacheKeyTheRootStartIsNotRunAgain() =
        warmDeepLink(rootCacheKey = { _ -> "session-1" }, expectedRootStarts = 1)
}
