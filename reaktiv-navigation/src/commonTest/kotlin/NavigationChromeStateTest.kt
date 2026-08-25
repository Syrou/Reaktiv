import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import io.github.syrou.reaktiv.core.createStore
import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.createNavigationModule
import io.github.syrou.reaktiv.navigation.definition.Graph
import io.github.syrou.reaktiv.navigation.definition.Modal
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.extension.navigation
import io.github.syrou.reaktiv.navigation.param.Params
import io.github.syrou.reaktiv.navigation.transition.NavTransition
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.DurationUnit
import kotlin.time.toDuration

@OptIn(ExperimentalCoroutinesApi::class)
class NavigationChromeStateTest {

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
    private val detail = screen("detail")
    private val stepOne = screen("step-one")
    private val stepTwo = screen("step-two")

    private val alert = object : Modal {
        override val route = "alert"
        override val enterTransition = NavTransition.None
        override val exitTransition = NavTransition.None

        @Composable
        override fun Content(params: Params) {
            Text("alert")
        }
    }

    private object StructuralGraph : Graph {
        override val route = "main"
    }

    private object SheetGraph : Graph {
        override val route = "wizard"
        override val enterTransition = NavTransition.SlideUpBottom
    }

    private fun createModule() = createNavigationModule {
        rootGraph {
            start(home)
            screens(home)
            modals(alert)
            graph(StructuralGraph) {
                start(detail)
                screens(detail)
            }
            graph(SheetGraph) {
                start(stepOne)
                screens(stepOne, stepTwo)
            }
        }
    }

    @Test
    fun a_presenting_graph_declares_that_it_carries_its_own_chrome() {
        assertFalse(
            SheetGraph.showsNavigationChrome,
            "a graph arriving from the bottom is a sheet, and a sheet owns its header"
        )
        assertTrue(
            StructuralGraph.showsNavigationChrome,
            "a graph with no presentation of its own is grouping, not a surface"
        )
    }

    @Test
    fun chrome_is_wanted_inside_a_structural_graph() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val store = createStore {
                module(createModule())
                coroutineContext(StandardTestDispatcher(testScheduler))
            }
            store.navigation { navigateTo("main/detail") }
            advanceUntilIdle()

            assertTrue(store.selectState<NavigationState>().first().showsNavigationChrome)
        }

    @Test
    fun chrome_is_not_wanted_anywhere_inside_a_presenting_graph() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val store = createStore {
                module(createModule())
                coroutineContext(StandardTestDispatcher(testScheduler))
            }
            store.navigation { navigateTo("wizard") }
            advanceUntilIdle()
            assertFalse(
                store.selectState<NavigationState>().first().showsNavigationChrome,
                "the first step is already inside the sheet"
            )

            store.navigation { navigateTo("wizard/step-two") }
            advanceUntilIdle()
            assertFalse(
                store.selectState<NavigationState>().first().showsNavigationChrome,
                "and so is every step after it, the opt out belongs to the graph not the screen"
            )
        }

    @Test
    fun chrome_returns_when_the_presenting_graph_is_left() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val store = createStore {
                module(createModule())
                coroutineContext(StandardTestDispatcher(testScheduler))
            }
            store.navigation { navigateTo("wizard") }
            advanceUntilIdle()
            store.navigation { navigateTo("home") }
            advanceUntilIdle()

            assertTrue(store.selectState<NavigationState>().first().showsNavigationChrome)
        }

    @Test
    fun a_modal_suppresses_chrome_without_any_declaration() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val store = createStore {
                module(createModule())
                coroutineContext(StandardTestDispatcher(testScheduler))
            }
            store.navigation { navigateTo("alert") }
            advanceUntilIdle()

            assertFalse(
                store.selectState<NavigationState>().first().showsNavigationChrome,
                "a modal covers the header it would otherwise sit under"
            )
        }
}
