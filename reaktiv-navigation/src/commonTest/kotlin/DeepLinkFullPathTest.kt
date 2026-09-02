import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import io.github.syrou.reaktiv.core.Store
import io.github.syrou.reaktiv.core.createStore
import io.github.syrou.reaktiv.core.util.selectState
import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.createNavigationModule
import io.github.syrou.reaktiv.navigation.definition.LoadingModal
import io.github.syrou.reaktiv.navigation.definition.NavigationPath
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.exception.RouteNotFoundException
import io.github.syrou.reaktiv.navigation.extension.navigateDeepLink
import io.github.syrou.reaktiv.navigation.param.Params
import io.github.syrou.reaktiv.navigation.transition.NavTransition
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.DurationUnit
import kotlin.time.toDuration

@OptIn(ExperimentalCoroutinesApi::class)
class DeepLinkFullPathTest {

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

    private val splash = screen("splash")
    private val login = screen("login")
    private val releaseOverview = screen("release-overview")
    private val releaseInfo = screen("release-info/{release-id}")
    private val insightHub = screen("insight-hub")

    private fun module() = createNavigationModule {
        loadingModal(loadingScreen)
        rootGraph {
            start(splash)
            screens(splash, login)
            graph("home") {
                start(releaseOverview)
                graph("releases") {
                    start(releaseOverview)
                    screens(releaseOverview, releaseInfo)
                }
                graph("insight") {
                    start(route = { _ -> NavigationPath("home/insight/insight-hub") })
                    screens(insightHub)
                }
            }
        }
    }

    private fun withStore(block: suspend (Store) -> Unit) =
        runTest(timeout = 20.toDuration(DurationUnit.SECONDS)) {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val store = createStore {
                module(module())
                coroutineContext(dispatcher)
            }
            advanceUntilIdle()
            block(store)
            advanceUntilIdle()
        }

    private suspend fun Store.currentPath(): String =
        selectState<NavigationState>().first().currentEntry.path

    @Test
    fun bareRouteOfNestedScreenIsRejectedWithTheFullPathNamed() = withStore { store ->
        val error = assertFailsWith<RouteNotFoundException> {
            store.navigateDeepLink("release-overview")
        }
        assertTrue("home/releases/release-overview" in (error.message ?: ""), error.message)
        assertEquals("splash", store.currentPath())
    }

    @Test
    fun bareIdOfNestedGraphIsRejected() = withStore { store ->
        val error = assertFailsWith<RouteNotFoundException> {
            store.navigateDeepLink("insight")
        }
        assertTrue("home/insight" in (error.message ?: ""), error.message)
        assertEquals("splash", store.currentPath())
    }

    @Test
    fun rootLevelRouteIsItsOwnFullPath() = withStore { store ->
        store.navigateDeepLink("login")
        assertEquals("login", store.currentPath())
    }

    @Test
    fun fullGraphPathOfDynamicGraphIsAccepted() = withStore { store ->
        store.navigateDeepLink("home/insight")
        assertEquals("home/insight/insight-hub", store.currentPath())
    }

    @Test
    fun parameterizedFullPathWithValueIsAccepted() = withStore { store ->
        store.navigateDeepLink("home/releases/release-info/42")
        val state = store.selectState<NavigationState>().first()
        assertEquals("home/releases/release-info/{release-id}", state.currentEntry.path)
        assertEquals("42", state.currentEntry.params.getString("release-id"))
    }

    @Test
    fun aliasWithPartialTargetFailsWhenTheModuleIsBuilt() {
        val module = createNavigationModule {
            rootGraph {
                start(splash)
                screens(splash)
                graph("home") {
                    graph("releases") {
                        screens(releaseOverview)
                    }
                }
            }
            deepLinkAliases {
                alias("studio", "releases/release-overview") { Params.empty() }
            }
        }
        val error = assertFailsWith<IllegalArgumentException> { module.getAllFullPaths() }
        assertTrue("home/releases/release-overview" in (error.message ?: ""), error.message)
    }
}
