import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import io.github.syrou.reaktiv.core.createStore
import io.github.syrou.reaktiv.navigation.NavigationAction
import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.createNavigationModule
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.extension.navigation
import io.github.syrou.reaktiv.navigation.model.NavigationEntry
import io.github.syrou.reaktiv.navigation.param.Params
import io.github.syrou.reaktiv.navigation.transition.NavTransition
import io.github.syrou.reaktiv.navigation.util.determineAnimationDecision
import io.github.syrou.reaktiv.navigation.util.impliesBackNavigation
import io.github.syrou.reaktiv.navigation.util.lastStackChange
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.DurationUnit
import kotlin.time.toDuration

@OptIn(ExperimentalCoroutinesApi::class)
class NavigationDirectionTest {

    private fun screen(route: String) = object : Screen {
        override val route = route
        override val enterTransition = NavTransition.SlideInRight
        override val exitTransition = NavTransition.SlideOutLeft

        @Composable
        override fun Content(params: Params) {
            Text(route)
        }
    }

    private val first = screen("first")
    private val second = screen("second")
    private val third = screen("third")

    private val someEntry = NavigationEntry(first, "first", Params.empty())

    @Test
    fun `back and a pop without a re-added entry are backward while everything else is not`() {
        assertTrue(NavigationAction.Back().impliesBackNavigation())
        assertTrue(NavigationAction.PopUpTo("first", inclusive = false).impliesBackNavigation())
        assertFalse(NavigationAction.PopUpTo("first", inclusive = false, entryToReAdd = someEntry).impliesBackNavigation())
        assertFalse(NavigationAction.Navigate(someEntry).impliesBackNavigation())
        assertFalse(NavigationAction.ClearBackstack.impliesBackNavigation())
        assertFalse(null.impliesBackNavigation())
    }

    @Test
    fun `the last stack change in a batch decides as it does for the reducer`() {
        val backThenForward = listOf(NavigationAction.Back(), NavigationAction.Navigate(someEntry))
        val forwardThenPop = listOf(NavigationAction.Navigate(someEntry), NavigationAction.PopUpTo("first", false))
        val withBookkeeping = listOf(NavigationAction.Back(), NavigationAction.ClearPendingNavigation)

        assertFalse(backThenForward.lastStackChange().impliesBackNavigation())
        assertTrue(forwardThenPop.lastStackChange().impliesBackNavigation())
        assertTrue(withBookkeeping.lastStackChange().impliesBackNavigation())
    }

    @Test
    fun `popping up to the root animates as a pop`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val store = createStore {
                module(
                    createNavigationModule {
                        rootGraph {
                            start(first)
                            screens(first, second, third)
                        }
                    }
                )
                coroutineContext(StandardTestDispatcher(testScheduler))
            }
            store.navigation { navigateTo("second") }
            store.navigation { navigateTo("third") }
            advanceUntilIdle()
            val before = store.selectState<NavigationState>().first().orderedBackStack.last()

            store.navigation { popUpTo("first") }
            advanceUntilIdle()

            val state = store.selectState<NavigationState>().first()
            val after = state.orderedBackStack.last()
            assertEquals("first", after.route)
            val decision = determineAnimationDecision(
                before,
                after,
                emptyMap(),
                isExplicitBackNavigation = state.lastNavigationAction.impliesBackNavigation()
            )
            assertFalse(decision.isForward)
        }
}
