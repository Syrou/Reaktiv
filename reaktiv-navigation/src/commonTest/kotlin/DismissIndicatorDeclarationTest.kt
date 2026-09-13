import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import io.github.syrou.reaktiv.core.createStore
import io.github.syrou.reaktiv.navigation.NavigationModule
import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.createNavigationModule
import io.github.syrou.reaktiv.navigation.definition.DismissIndicatorPlacement
import io.github.syrou.reaktiv.navigation.definition.Graph
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.extension.navigation
import io.github.syrou.reaktiv.navigation.model.NavigationEntry
import io.github.syrou.reaktiv.navigation.param.Params
import io.github.syrou.reaktiv.navigation.transition.NavTransition
import io.github.syrou.reaktiv.navigation.util.IndicatorAnchor
import io.github.syrou.reaktiv.navigation.util.canArmSwipeDismiss
import io.github.syrou.reaktiv.navigation.util.dismissIndicatorAnchor
import io.github.syrou.reaktiv.navigation.util.findLayoutGraphsInHierarchy
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.DurationUnit
import kotlin.time.toDuration

@OptIn(ExperimentalCoroutinesApi::class)
class DismissIndicatorDeclarationTest {

    private fun screen(name: String, enter: NavTransition, showsHandle: Boolean = true) = object : Screen {
        override val route = name
        override val enterTransition = enter
        override val exitTransition = NavTransition.None
        override val showsDismissIndicator = showsHandle

        @Composable
        override fun Content(params: Params) {
            Text(name)
        }
    }

    private val home = screen("home", NavTransition.None)
    private val sheet = screen("sheet", NavTransition.SlideUpBottom)
    private val handleFreeSheet = screen("handle-free", NavTransition.SlideUpBottom, showsHandle = false)
    private val surfaceStep = screen("surface-step", NavTransition.SlideInRight)
    private val bareStep = screen("bare-step", NavTransition.SlideInRight)
    private val flowStep = screen("flow-step", NavTransition.SlideInRight)
    private val chromeSheet = screen("chrome-sheet", NavTransition.SlideUpBottom)

    private val pinnedSheet = object : Screen {
        override val route = "pinned-sheet"
        override val enterTransition = NavTransition.SlideUpBottom
        override val exitTransition = NavTransition.SlideOutBottom
        override val dismissIndicatorPlacement = DismissIndicatorPlacement.Surface

        @Composable
        override fun Content(params: Params) {
            Text("pinned")
        }
    }

    private object SurfaceGraph : Graph {
        override val route = "surface"
        override val enterTransition = NavTransition.SlideUpBottom
        override val exitTransition = NavTransition.SlideOutBottom
    }

    private object BareSurfaceGraph : Graph {
        override val route = "bare"
        override val enterTransition = NavTransition.SlideUpBottom
        override val exitTransition = NavTransition.SlideOutBottom
    }

    private object HandleFreeGraph : Graph {
        override val route = "flow"
        override val enterTransition = NavTransition.SlideUpBottom
        override val exitTransition = NavTransition.SlideOutBottom
        override val showsDismissIndicator = false
    }

    private fun createModule() = createNavigationModule {
        rootGraph {
            start(home)
            screens(home, sheet, handleFreeSheet)
            graph(SurfaceGraph) {
                start(surfaceStep)
                screens(surfaceStep)
                layout { content -> content() }
            }
            graph(BareSurfaceGraph) {
                start(bareStep)
                screens(bareStep)
            }
            graph(HandleFreeGraph) {
                start(flowStep)
                screens(flowStep)
            }
            graph("chrome") {
                start(chromeSheet)
                screens(chromeSheet, pinnedSheet)
                layout { content -> content() }
            }
        }
    }

    private fun layoutRoutesOf(entry: NavigationEntry, navModule: NavigationModule): List<String> {
        val graphId = navModule.getGraphId(entry) ?: entry.route
        return findLayoutGraphsInHierarchy(graphId, navModule.getGraphDefinitions()).map { it.route }
    }

    private fun assertAnchorAt(route: String, expected: IndicatorAnchor?) =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val navModule = createModule()
            val store = createStore {
                module(navModule)
                coroutineContext(StandardTestDispatcher(testScheduler))
            }
            store.navigation { navigateTo(route) }
            advanceUntilIdle()
            val state = store.selectState<NavigationState>().first()
            val entry = state.currentEntry

            assertEquals(
                expected,
                dismissIndicatorAnchor(entry, navModule, layoutRoutesOf(entry, navModule)),
                route
            )
            assertTrue(canArmSwipeDismiss(state, navModule), "$route stays draggable")
        }

    @Test
    fun a_screen_with_no_chrome_around_it_anchors_above_its_own_content() =
        assertAnchorAt("sheet", IndicatorAnchor.OwnContent)

    @Test
    fun a_screen_declining_the_handle_anchors_nowhere() =
        assertAnchorAt("handle-free", null)

    @Test
    fun a_graph_declining_the_handle_anchors_nowhere_for_its_steps() =
        assertAnchorAt("flow/flow-step", null)

    @Test
    fun a_graph_dragged_as_one_surface_anchors_above_the_chrome_it_declares() =
        assertAnchorAt("surface/surface-step", IndicatorAnchor.Layout("surface"))

    @Test
    fun a_graph_dragged_as_one_surface_without_chrome_anchors_above_its_content() =
        assertAnchorAt("bare/bare-step", IndicatorAnchor.OwnContent)

    @Test
    fun a_screen_under_chrome_that_stays_anchors_above_that_chrome_by_default() =
        assertAnchorAt("chrome/chrome-sheet", IndicatorAnchor.Layout("chrome"))

    @Test
    fun a_screen_pinning_the_handle_to_the_surface_anchors_above_its_own_content() =
        assertAnchorAt("chrome/pinned-sheet", IndicatorAnchor.OwnContent)
}
