import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import io.github.syrou.reaktiv.core.createStore
import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.createNavigationModule
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.extension.navigation
import io.github.syrou.reaktiv.navigation.param.Params
import io.github.syrou.reaktiv.navigation.transition.NavTransition
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.DurationUnit
import kotlin.time.toDuration

@OptIn(ExperimentalCoroutinesApi::class)
class RouteMatchingTest {

    private fun screen(route: String) = object : Screen {
        override val route = route
        override val enterTransition = NavTransition.None
        override val exitTransition = NavTransition.None

        @Composable
        override fun Content(params: Params) {
            Text(route)
        }
    }

    private val slugScreen = screen("{slug}")
    private val localizedHome = screen("{lang}/home")
    private val englishPage = screen("en/{page}")
    private val userScreen = screen("user/{name}")
    private val tagScreen = screen("tag/{tag}")

    private fun TestScope.store() = createStore {
        module(
            createNavigationModule {
                rootGraph {
                    start(SplashScreen)
                    screens(SettingsScreen, slugScreen, localizedHome, englishPage, userScreen, tagScreen)
                }
            }
        )
        coroutineContext(StandardTestDispatcher(testScheduler))
    }

    @Test
    fun `a root route that starts with a placeholder is reachable while static routes still win`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val store = store()

            store.navigation { navigateTo("about") }
            advanceUntilIdle()
            var state = store.selectState<NavigationState>().first()
            assertEquals("{slug}", state.currentEntry.route)
            assertEquals("about", state.currentEntry.params["slug"])

            store.navigation { navigateTo("settings") }
            advanceUntilIdle()
            state = store.selectState<NavigationState>().first()
            assertEquals("settings", state.currentEntry.route)
        }

    @Test
    fun `the route with a static segment earlier wins when two templates match`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val store = store()

            store.navigation { navigateTo("en/home") }
            advanceUntilIdle()

            val state = store.selectState<NavigationState>().first()
            assertEquals("en/{page}", state.currentEntry.route)
            assertEquals("home", state.currentEntry.params["page"])
        }

    @Test
    fun `path values are decoded once and a plus stays a plus`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val store = store()

            store.navigation { navigateTo("user/John%20Doe") }
            advanceUntilIdle()
            var state = store.selectState<NavigationState>().first()
            assertEquals("John Doe", state.currentEntry.params["name"])
            assertEquals("user/John Doe", state.currentFullPath)

            store.navigation { navigateTo("tag/c+c?q=a+b") }
            advanceUntilIdle()
            state = store.selectState<NavigationState>().first()
            assertEquals("c+c", state.currentEntry.params["tag"])
            assertEquals("a b", state.currentEntry.params["q"])
        }
}
