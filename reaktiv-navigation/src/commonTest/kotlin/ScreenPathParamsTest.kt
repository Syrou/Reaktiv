import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import io.github.syrou.reaktiv.core.createStore
import io.github.syrou.reaktiv.navigation.NavigationModule
import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.createNavigationModule
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.exception.MissingPathParamsException
import io.github.syrou.reaktiv.navigation.exception.RouteNotFoundException
import io.github.syrou.reaktiv.navigation.extension.navigation
import io.github.syrou.reaktiv.navigation.param.Params
import io.github.syrou.reaktiv.navigation.transition.NavTransition
import io.github.syrou.reaktiv.navigation.util.locationOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.DurationUnit
import kotlin.time.toDuration

@OptIn(ExperimentalCoroutinesApi::class)
class ScreenPathParamsTest {

    private fun screen(route: String) = object : Screen {
        override val route = route
        override val enterTransition = NavTransition.None
        override val exitTransition = NavTransition.None

        @Composable
        override fun Content(params: Params) {
            Text(route)
        }
    }

    private val homeScreen = screen("overview")
    private val boardScreen = screen("board")
    private val playerScreen = screen("player/{playerId}")
    private val unregisteredScreen = screen("nowhere")

    private val navigationModule: NavigationModule = createNavigationModule {
        rootGraph {
            start(SplashScreen)
            graph("home") {
                start(homeScreen)
                graph("leaderboard") {
                    start(boardScreen)
                    screens(playerScreen)
                }
            }
        }
    }

    private fun TestScope.store() = createStore {
        module(navigationModule)
        coroutineContext(StandardTestDispatcher(testScheduler))
    }

    @Test
    fun `a nested screen is reached from its object and values without its graph path`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val store = store()

            store.navigation { navigateTo(playerScreen, "playerId" to 7) }
            advanceUntilIdle()

            val entry = store.selectState<NavigationState>().first().currentEntry
            assertEquals("home/leaderboard/player/7", entry.location)
            assertEquals(7, entry.params.getInt("playerId"))
        }

    @Test
    fun `a path param given as a number arrives as text like one parsed from a url`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val store = store()

            store.navigation { navigateTo(playerScreen, "playerId" to 7) }
            advanceUntilIdle()
            val fromObject = store.selectState<NavigationState>().first().currentEntry
            store.navigation { navigateTo("home/leaderboard/player/7") }
            advanceUntilIdle()
            val fromPath = store.selectState<NavigationState>().first().currentEntry

            assertEquals("7", fromObject.params["playerId"])
            assertEquals(fromPath.params["playerId"], fromObject.params["playerId"])
            assertEquals(fromPath.stableKey, fromObject.stableKey)
        }

    @Test
    fun `the parameter block form fills path params too`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val store = store()

            store.navigation { navigateTo(playerScreen) { put("playerId", "8") } }
            advanceUntilIdle()

            assertEquals("home/leaderboard/player/8", store.selectState<NavigationState>().first().currentEntry.location)
        }

    @Test
    fun `a missing path param fails the navigation and names the key`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val store = store()
            advanceUntilIdle()
            val before = store.selectState<NavigationState>().first().currentEntry.location

            val byObject = assertFailsWith<MissingPathParamsException> {
                store.navigation { navigateTo(playerScreen) }
            }
            val byTemplate = assertFailsWith<MissingPathParamsException> {
                store.navigation { navigateTo("home/leaderboard/player/{playerId}") }
            }
            advanceUntilIdle()

            assertEquals(listOf("playerId"), byObject.missingParams)
            assertEquals(listOf("playerId"), byTemplate.missingParams)
            assertTrue(byObject.message!!.contains("playerId"))
            assertEquals(before, store.selectState<NavigationState>().first().currentEntry.location)
        }

    @Test
    fun `locationOf builds the location the navigation lands on and encodes values`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val store = store()

            val location = store.locationOf(playerScreen, "playerId" to "a b/c")
            assertEquals("home/leaderboard/player/a%20b%2Fc", location)

            store.navigation { navigateTo(location) }
            advanceUntilIdle()

            val entry = store.selectState<NavigationState>().first().currentEntry
            assertEquals(location, entry.location)
            assertEquals("a b/c", entry.params.getString("playerId"))
        }

    @Test
    fun `locationOf reports a missing value and an unregistered screen`() {
        assertFailsWith<MissingPathParamsException> { navigationModule.locationOf(playerScreen) }
        assertFailsWith<RouteNotFoundException> { navigationModule.locationOf(unregisteredScreen) }
    }
}
