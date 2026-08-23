import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import io.github.syrou.reaktiv.core.createStore
import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.createNavigationModule
import io.github.syrou.reaktiv.navigation.definition.Graph
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.extension.navigateBack
import io.github.syrou.reaktiv.navigation.extension.navigation
import io.github.syrou.reaktiv.navigation.param.Params
import io.github.syrou.reaktiv.navigation.transition.NavTransition
import io.github.syrou.reaktiv.navigation.util.revealedEntryForDismiss
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.DurationUnit
import kotlin.time.toDuration

/**
 * Back and dismiss are different intents inside a presented graph.
 *
 * Stepping backwards through a wizard is not the same as throwing the wizard away, so a plain back
 * request walks the history one entry at a time while only the drag collapses the whole graph.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BackVersusDismissTest {

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
    fun back_from_the_middle_of_a_wizard_steps_back_within_it() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val navModule = createModule()
            val store = createStore {
                module(navModule)
                coroutineContext(StandardTestDispatcher(testScheduler))
            }
            store.navigation { navigateTo("wizard") }
            store.navigation { navigateTo("wizard/step-two") }
            advanceUntilIdle()

            store.navigateBack()
            advanceUntilIdle()

            assertEquals(
                "step-one",
                store.selectState<NavigationState>().first().currentEntry.navigatable.route,
                "back walks the wizard history rather than discarding the wizard"
            )
        }

    @Test
    fun back_repeatedly_eventually_leaves_the_wizard_one_step_at_a_time() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val navModule = createModule()
            val store = createStore {
                module(navModule)
                coroutineContext(StandardTestDispatcher(testScheduler))
            }
            store.navigation { navigateTo("wizard") }
            store.navigation { navigateTo("wizard/step-two") }
            advanceUntilIdle()

            store.navigateBack()
            advanceUntilIdle()
            store.navigateBack()
            advanceUntilIdle()

            assertEquals(
                "home",
                store.selectState<NavigationState>().first().currentEntry.navigatable.route
            )
        }

    @Test
    fun the_drag_target_still_collapses_the_whole_wizard() =
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
                "home",
                revealedEntryForDismiss(state, navModule)?.navigatable?.route,
                "the drag still targets what sits below the whole graph"
            )
        }
}
