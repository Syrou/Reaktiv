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
import io.github.syrou.reaktiv.navigation.ui.dismissSurface
import io.github.syrou.reaktiv.navigation.util.canArmInteractiveBackGesture
import io.github.syrou.reaktiv.navigation.util.canArmSwipeDismiss
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
import kotlin.test.assertTrue
import kotlin.time.DurationUnit
import kotlin.time.toDuration

@OptIn(ExperimentalCoroutinesApi::class)
class GestureCommitTargetTest {

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

    private object SheetGraph : Graph {
        override val route = "wizard"
        override val enterTransition = NavTransition.SlideUpBottom
    }

    private fun createModule() = createNavigationModule {
        rootGraph {
            start(home)
            screens(home)
            graph(SheetGraph) {
                start(stepOne)
                screens(stepOne, stepTwo)
            }
        }
    }

    @Test
    fun an_edge_swipe_inside_the_graph_steps_back_one_entry() =
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

            dismissSurface(
                store = store,
                navModule = navModule,
                top = state.currentEntry,
                revealed = revealedEntryForBack(state)
            )
            advanceUntilIdle()

            assertEquals(
                "step-one",
                store.selectState<NavigationState>().first().currentEntry.navigatable.route,
                "a back swipe must not inherit dismiss semantics just because it shares the commit"
            )
        }

    @Test
    fun a_drag_inside_the_graph_unwinds_past_the_whole_graph() =
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

            dismissSurface(
                store = store,
                navModule = navModule,
                top = state.currentEntry,
                revealed = revealedEntryForDismiss(state, navModule)
            )
            advanceUntilIdle()

            assertEquals(
                "home",
                store.selectState<NavigationState>().first().currentEntry.navigatable.route,
                "the drag animated toward what sits below the graph, so it lands there"
            )
        }

    @Test
    fun a_gesture_outside_any_presented_graph_still_pops_one_entry() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val navModule = createModule()
            val store = createStore {
                module(navModule)
                coroutineContext(StandardTestDispatcher(testScheduler))
            }
            store.navigation { navigateTo("wizard") }
            advanceUntilIdle()
            val state = store.selectState<NavigationState>().first()

            dismissSurface(
                store = store,
                navModule = navModule,
                top = state.currentEntry,
                revealed = revealedEntryForBack(state)
            )
            advanceUntilIdle()

            assertEquals(
                "home",
                store.selectState<NavigationState>().first().currentEntry.navigatable.route
            )
        }

    @Test
    fun the_horizontal_swipe_does_not_arm_on_the_first_screen_of_a_presented_graph() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val navModule = createModule()
            val store = createStore {
                module(navModule)
                coroutineContext(StandardTestDispatcher(testScheduler))
            }
            store.navigation { navigateTo("wizard") }
            advanceUntilIdle()
            val state = store.selectState<NavigationState>().first()

            assertFalse(
                canArmInteractiveBackGesture(state, navModule),
                "leaving the first step leaves the graph, which arrived vertically"
            )
            assertTrue(
                canArmSwipeDismiss(state, navModule),
                "the vertical drag is the way out of a sheet"
            )
        }

    @Test
    fun the_horizontal_swipe_still_arms_between_steps_inside_the_graph() =
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

            assertTrue(
                canArmInteractiveBackGesture(state, navModule),
                "no boundary is crossed between steps, so the step decides and it is horizontal"
            )
            assertTrue(
                canArmSwipeDismiss(state, navModule),
                "the sheet can still be thrown away from any step"
            )
        }
}
