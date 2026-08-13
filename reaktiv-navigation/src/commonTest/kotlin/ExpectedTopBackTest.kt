import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import io.github.syrou.reaktiv.core.createStore
import io.github.syrou.reaktiv.core.util.selectState
import io.github.syrou.reaktiv.navigation.NavigationAction
import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.createNavigationModule
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.extension.navigation
import io.github.syrou.reaktiv.navigation.param.Params
import io.github.syrou.reaktiv.navigation.transition.NavTransition
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ExpectedTopBackTest {

    private fun screen(name: String) = object : Screen {
        override val route = name
        override val enterTransition = NavTransition.None
        override val exitTransition = NavTransition.None

        @Composable
        override fun Content(params: Params) {
            Text(name)
        }
    }

    private val home = screen("home")
    private val first = screen("first")
    private val second = screen("second")

    private fun store(dispatcher: CoroutineDispatcher) = createStore {
        coroutineContext(dispatcher)
        module(
            createNavigationModule {
                rootGraph {
                    start(home)
                    screens(home, first, second)
                }
            }
        )
    }

    @Test
    fun `back naming the current top pops it`() = runTest {
        val store = store(StandardTestDispatcher(testScheduler))
        advanceUntilIdle()
        store.navigation { navigateTo("first") }
        advanceUntilIdle()

        val top = store.selectState<NavigationState>().first().currentEntry
        store.dispatch(NavigationAction.Back(expectedTopKey = top.stableKey))
        advanceUntilIdle()

        assertEquals("home", store.selectState<NavigationState>().first().currentEntry.route)
        store.cleanup()
    }

    @Test
    fun `back naming a stale top is dropped rather than popping the wrong entry`() = runTest {
        val store = store(StandardTestDispatcher(testScheduler))
        advanceUntilIdle()
        store.navigation { navigateTo("first") }
        advanceUntilIdle()

        val staleTop = store.selectState<NavigationState>().first().currentEntry

        store.navigation { navigateTo("second") }
        advanceUntilIdle()
        assertEquals("second", store.selectState<NavigationState>().first().currentEntry.route)

        store.dispatch(NavigationAction.Back(expectedTopKey = staleTop.stableKey))
        advanceUntilIdle()

        assertEquals(
            "second",
            store.selectState<NavigationState>().first().currentEntry.route,
            "A back that named a stale top must not pop the entry that replaced it"
        )
        store.cleanup()
    }

    @Test
    fun `back without an expectation still pops whatever is current`() = runTest {
        val store = store(StandardTestDispatcher(testScheduler))
        advanceUntilIdle()
        store.navigation { navigateTo("first") }
        advanceUntilIdle()
        store.navigation { navigateTo("second") }
        advanceUntilIdle()

        store.dispatch(NavigationAction.Back())
        advanceUntilIdle()

        assertEquals(
            "first",
            store.selectState<NavigationState>().first().currentEntry.route,
            "Hardware and programmatic back keep their existing behaviour"
        )
        store.cleanup()
    }
}
