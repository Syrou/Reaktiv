import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import io.github.syrou.reaktiv.core.createStore
import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.createNavigationModule
import io.github.syrou.reaktiv.navigation.definition.Graph
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.extension.navigation
import io.github.syrou.reaktiv.navigation.param.Params
import io.github.syrou.reaktiv.navigation.transition.NavTransition
import io.github.syrou.reaktiv.navigation.util.canArmSwipeDismiss
import io.github.syrou.reaktiv.navigation.util.determineAnimationDecision
import io.github.syrou.reaktiv.navigation.util.dismissableBoundary
import io.github.syrou.reaktiv.navigation.util.revealedEntryForBack
import io.github.syrou.reaktiv.navigation.util.revealedEntryForDismiss
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.DurationUnit
import kotlin.time.toDuration

/**
 * A wizard presented as a sheet: it arrives vertically as one surface, its steps move horizontally
 * inside it, and dragging it away takes the whole graph rather than stepping back through it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GraphDismissBoundaryTest {

    private fun screen(name: String) = object : Screen {
        override val route = name
        override val enterTransition = NavTransition.SlideInRight
        override val exitTransition = NavTransition.SlideOutLeft

        @Composable
        override fun Content(params: Params) {
            Text(name)
        }
    }

    private val home = screen("home")
    private val stepOne = screen("step-one")
    private val stepTwo = screen("step-two")
    private val plainInner = screen("plain-inner")

    private object SheetGraph : Graph {
        override val route = "wizard"
        override val enterTransition = NavTransition.SlideUpBottom
    }

    private object PlainGraph : Graph {
        override val route = "plain"
    }

    private fun createModule() = createNavigationModule {
        rootGraph {
            start(home)
            screens(home)
            graph(SheetGraph) {
                start(stepOne)
                screens(stepOne, stepTwo)
            }
            graph(PlainGraph) {
                start(plainInner)
                screens(plainInner)
            }
        }
    }

    @Test
    fun every_step_of_a_presented_graph_is_inside_the_dismiss_boundary() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val navModule = createModule()
            val store = createStore {
                module(navModule)
                coroutineContext(StandardTestDispatcher(testScheduler))
            }
            store.navigation { navigateTo("wizard") }
            advanceUntilIdle()
            var state = store.selectState<NavigationState>().first()
            assertEquals("wizard", dismissableBoundary(state.currentEntry, navModule))

            store.navigation { navigateTo("wizard/step-two") }
            advanceUntilIdle()
            state = store.selectState<NavigationState>().first()
            assertEquals(
                "wizard",
                dismissableBoundary(state.currentEntry, navModule),
                "a horizontal step is still inside the sheet that was dragged open"
            )
        }

    @Test
    fun a_graph_without_a_presentation_is_not_a_boundary() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val navModule = createModule()
            val store = createStore {
                module(navModule)
                coroutineContext(StandardTestDispatcher(testScheduler))
            }
            store.navigation { navigateTo("plain") }
            advanceUntilIdle()
            val state = store.selectState<NavigationState>().first()

            assertEquals(null, dismissableBoundary(state.currentEntry, navModule))
            assertFalse(canArmSwipeDismiss(state, navModule))
        }

    @Test
    fun the_drag_stays_armed_on_a_horizontal_step_inside_the_sheet() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val navModule = createModule()
            val store = createStore {
                module(navModule)
                coroutineContext(StandardTestDispatcher(testScheduler))
            }
            store.navigation { navigateTo("wizard") }
            advanceUntilIdle()
            assertTrue(canArmSwipeDismiss(store.selectState<NavigationState>().first(), navModule))

            store.navigation { navigateTo("wizard/step-two") }
            advanceUntilIdle()
            assertTrue(
                canArmSwipeDismiss(store.selectState<NavigationState>().first(), navModule),
                "the affordance belongs to the sheet, not to whichever step is showing"
            )
        }

    @Test
    fun dismissing_from_a_later_step_reveals_what_sits_below_the_whole_graph() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val navModule = createModule()
            val store = createStore {
                module(navModule)
                coroutineContext(StandardTestDispatcher(testScheduler))
            }
            store.navigation { navigateTo("wizard") }
            store.navigation { navigateTo("wizard/step-two") }
            advanceUntilIdle()
            val state = store.selectState<NavigationState>().first()

            assertEquals(
                "step-one",
                revealedEntryForBack(state)?.navigatable?.route,
                "back still means back, one step at a time"
            )
            assertEquals(
                "home",
                revealedEntryForDismiss(state, navModule)?.navigatable?.route,
                "dismissing takes the sheet away, revealing what was underneath it"
            )
        }

    @Test
    fun dismissing_from_a_later_step_animates_with_the_graph_transition() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val navModule = createModule()
            val store = createStore {
                module(navModule)
                coroutineContext(StandardTestDispatcher(testScheduler))
            }
            store.navigation { navigateTo("wizard") }
            store.navigation { navigateTo("wizard/step-two") }
            advanceUntilIdle()
            val state = store.selectState<NavigationState>().first()
            val revealed = revealedEntryForDismiss(state, navModule)
            assertNotNull(revealed)

            val decision = determineAnimationDecision(
                previousEntry = state.currentEntry,
                currentEntry = revealed,
                navModule = navModule,
                isExplicitBackNavigation = true
            )

            // Popping reverses the arrival rather than playing a separate exit, so the sheet
            // that slid up plays that same transition backwards and drops back down.
            assertEquals(
                NavTransition.SlideUpBottom,
                decision.exitTransition,
                "the sheet leaves by reversing its own arrival, not by the step sideways exit"
            )
            assertTrue(decision.exitReversed, "the arrival is played backwards")
        }

    @Test
    fun stepping_inside_the_graph_still_animates_with_the_screen_transition() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val navModule = createModule()
            val store = createStore {
                module(navModule)
                coroutineContext(StandardTestDispatcher(testScheduler))
            }
            store.navigation { navigateTo("wizard") }
            advanceUntilIdle()
            val first = store.selectState<NavigationState>().first().currentEntry

            store.navigation { navigateTo("wizard/step-two") }
            advanceUntilIdle()
            val second = store.selectState<NavigationState>().first().currentEntry

            val decision = determineAnimationDecision(
                previousEntry = first,
                currentEntry = second,
                navModule = navModule
            )

            assertEquals(
                NavTransition.SlideInRight,
                decision.enterTransition,
                "no boundary is crossed within the graph, so the step decides"
            )
        }

    @Test
    fun entering_the_graph_animates_with_the_graph_transition_not_the_start_screen() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val navModule = createModule()
            val store = createStore {
                module(navModule)
                coroutineContext(StandardTestDispatcher(testScheduler))
            }
            advanceUntilIdle()
            val before = store.selectState<NavigationState>().first().currentEntry

            store.navigation { navigateTo("wizard") }
            advanceUntilIdle()
            val after = store.selectState<NavigationState>().first().currentEntry

            val decision = determineAnimationDecision(
                previousEntry = before,
                currentEntry = after,
                navModule = navModule
            )

            assertEquals(
                NavTransition.SlideUpBottom,
                decision.enterTransition,
                "the start screen declares SlideInRight, but the graph owns how it arrives"
            )
        }
}
