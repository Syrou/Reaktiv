import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import io.github.syrou.reaktiv.core.createStore
import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.createNavigationModule
import io.github.syrou.reaktiv.navigation.definition.Modal
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.extension.dismissModal
import io.github.syrou.reaktiv.navigation.extension.navigation
import io.github.syrou.reaktiv.navigation.model.NavigationEntry
import io.github.syrou.reaktiv.navigation.param.Params
import io.github.syrou.reaktiv.navigation.transition.NavTransition
import io.github.syrou.reaktiv.navigation.util.determineAnimationDecision
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.DurationUnit
import kotlin.time.toDuration

@OptIn(ExperimentalCoroutinesApi::class)
class EntryLocationTest {

    private fun screen(route: String) = object : Screen {
        override val route = route
        override val enterTransition = NavTransition.SlideInRight
        override val exitTransition = NavTransition.SlideOutLeft

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

    private val startScreen = screen("start")
    private val playerScreen = screen("player/{id}")
    private val statsScreen = screen("stats")
    private val userScreen = screen("user/{name}")
    private val noteModal = modal("note/{id}")

    private fun TestScope.store() = createStore {
        module(
            createNavigationModule {
                rootGraph {
                    start(startScreen)
                    screens(startScreen, playerScreen, statsScreen, userScreen)
                    modals(noteModal)
                }
            }
        )
        coroutineContext(StandardTestDispatcher(testScheduler))
    }

    @Test
    fun `location is the encoded concrete path while currentFullPath stays readable`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val store = store()

            store.navigation { navigateTo("user/John%20Doe") }
            advanceUntilIdle()

            val state = store.selectState<NavigationState>().first()
            assertEquals("user/John%20Doe", state.currentEntry.location)
            assertEquals("user/John Doe", state.currentFullPath)
            assertEquals("user/{name}", state.currentEntry.path)
        }

    @Test
    fun `popUpTo a location pops to that instance and popUpTo the template pops to the newest`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val store = store()
            store.navigation { navigateTo("player/1") }
            store.navigation { navigateTo("stats") }
            store.navigation { navigateTo("player/2") }
            store.navigation { navigateTo("stats") }
            advanceUntilIdle()

            store.navigation { popUpTo("player/1") }
            advanceUntilIdle()
            var state = store.selectState<NavigationState>().first()
            assertEquals("player/1", state.currentEntry.location)
            assertEquals(listOf("start", "player/1"), state.backStack.map { it.location })

            store.navigation { navigateTo("stats") }
            store.navigation { navigateTo("player/2") }
            store.navigation { navigateTo("stats") }
            advanceUntilIdle()
            store.navigation { popUpTo("player/{id}") }
            advanceUntilIdle()
            state = store.selectState<NavigationState>().first()
            assertEquals("player/2", state.currentEntry.location)
        }

    @Test
    fun `a navigated entry is re-added after popUpTo when only a different instance of its screen survives`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val store = store()
            store.navigation { navigateTo("player/1") }
            store.navigation { navigateTo("stats") }
            advanceUntilIdle()

            store.navigation {
                navigateTo("player/3")
                popUpTo("player/1")
            }
            advanceUntilIdle()

            val state = store.selectState<NavigationState>().first()
            assertEquals(listOf("start", "player/1", "player/3"), state.backStack.map { it.location })
        }

    @Test
    fun `dismissModal removes the entry it was given rather than the newest of its kind`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val store = store()
            store.navigation { navigateTo("note/1") }
            store.navigation { navigateTo("note/2") }
            advanceUntilIdle()
            val first = store.selectState<NavigationState>().first().backStack.single { it.location == "note/1" }

            store.dismissModal(first)
            advanceUntilIdle()

            val state = store.selectState<NavigationState>().first()
            assertTrue(state.backStack.none { it.location == "note/1" })
        }

    @Test
    fun `replacing an entry with another instance of the same screen animates`() {
        val previous = NavigationEntry(playerScreen, "player/{id}", Params.of("id" to "42"), 1)
        val current = NavigationEntry(playerScreen, "player/{id}", Params.of("id" to "43"), 1)

        val decision = determineAnimationDecision(previous, current, emptyMap())

        assertTrue(decision.shouldAnimateEnter)
    }
}
