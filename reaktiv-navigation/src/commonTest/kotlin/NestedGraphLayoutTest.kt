import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import io.github.syrou.reaktiv.core.createStore
import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.createNavigationModule
import io.github.syrou.reaktiv.navigation.definition.Graph
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.definition.ScreenGroup
import io.github.syrou.reaktiv.navigation.extension.navigation
import io.github.syrou.reaktiv.navigation.param.Params
import io.github.syrou.reaktiv.navigation.transition.NavTransition
import io.github.syrou.reaktiv.navigation.ui.decideLayoutSharing
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
class NestedGraphLayoutTest {

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
    private val nestedStart = screen("nested-start")
    private val nestedSecond = screen("nested-second")

    private object WizardGraph : Graph {
        override val route = "wizard"
        override val enterTransition = NavTransition.SlideUpBottom
    }

    private object NestedGraph : Graph {
        override val route = "extras"
        override val enterTransition = NavTransition.SlideInRight
    }

    private fun createModule() = createNavigationModule {
        rootGraph {
            start(home)
            screens(home)
            graph(WizardGraph) {
                start(stepOne)
                screens(stepOne)
                layout { content -> content() }
                graph(NestedGraph) {
                    start(nestedStart)
                    screens(nestedStart, nestedSecond)
                }
            }
        }
    }

    @Test
    fun the_parent_layout_is_still_in_the_hierarchy_of_a_nested_graph() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val navModule = createModule()
            val store = createStore {
                module(navModule)
                coroutineContext(StandardTestDispatcher(testScheduler))
            }
            store.navigation { navigateTo("wizard/extras") }
            advanceUntilIdle()

            val state = store.selectState<NavigationState>().first()
            val graphs = navModule.getGraphDefinitions()
            val graphId = navModule.getGraphId(state.currentEntry) ?: state.currentEntry.route

            assertEquals(
                listOf("wizard"),
                findLayoutGraphsInHierarchy(graphId, graphs).map { it.route },
                "the wizard layout is an ancestor of the nested graph, so it still applies"
            )
        }

    @Test
    fun entering_a_nested_graph_keeps_the_parent_layout_shared() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val navModule = createModule()
            val store = createStore {
                module(navModule)
                coroutineContext(StandardTestDispatcher(testScheduler))
            }
            store.navigation { navigateTo("wizard") }
            advanceUntilIdle()
            val graphs = navModule.getGraphDefinitions()
            val before = store.selectState<NavigationState>().first().currentEntry
            val beforeLayouts = findLayoutGraphsInHierarchy(
                navModule.getGraphId(before) ?: before.route, graphs
            ).map { it.route }

            store.navigation { navigateTo("wizard/extras") }
            advanceUntilIdle()
            val after = store.selectState<NavigationState>().first().currentEntry
            val afterLayouts = findLayoutGraphsInHierarchy(
                navModule.getGraphId(after) ?: after.route, graphs
            ).map { it.route }

            val sharing = decideLayoutSharing(
                currentLayoutRoutes = afterLayouts,
                previousLayoutRoutes = beforeLayouts,
                revealedLayoutRoutes = null,
                restingBackLayoutRoutes = null,
                shouldAnimateExit = true
            )

            assertTrue(
                "wizard" in sharing.sharedRoutes,
                "the wizard layout was already on screen and is still in scope, so it must not " +
                    "be torn down and rebuilt inside the arriving transition"
            )
        }

    @Test
    fun moving_between_screens_inside_the_nested_graph_keeps_it_shared_too() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val navModule = createModule()
            val store = createStore {
                module(navModule)
                coroutineContext(StandardTestDispatcher(testScheduler))
            }
            store.navigation { navigateTo("wizard/extras") }
            advanceUntilIdle()
            val graphs = navModule.getGraphDefinitions()
            val before = store.selectState<NavigationState>().first().currentEntry
            val beforeLayouts = findLayoutGraphsInHierarchy(
                navModule.getGraphId(before) ?: before.route, graphs
            ).map { it.route }

            store.navigation { navigateTo("wizard/extras/nested-second") }
            advanceUntilIdle()
            val after = store.selectState<NavigationState>().first().currentEntry
            val afterLayouts = findLayoutGraphsInHierarchy(
                navModule.getGraphId(after) ?: after.route, graphs
            ).map { it.route }

            val sharing = decideLayoutSharing(
                currentLayoutRoutes = afterLayouts,
                previousLayoutRoutes = beforeLayouts,
                revealedLayoutRoutes = null,
                restingBackLayoutRoutes = null,
                shouldAnimateExit = true
            )
            assertTrue("wizard" in sharing.sharedRoutes)
        }

    private val groupedOne = screen("grouped-one")
    private val groupedTwo = screen("grouped-two")

    private val groupedSteps = ScreenGroup(listOf(groupedOne, groupedTwo))

    private object GroupGraph : Graph {
        override val route = "grouped"
        override val enterTransition = NavTransition.SlideInRight
    }

    private fun groupModule() = createNavigationModule {
        rootGraph {
            start(home)
            screens(home)
            graph(WizardGraph) {
                start(stepOne)
                screens(stepOne)
                screenGroup(groupedSteps)
                layout { content -> content() }
                graph(GroupGraph) {
                    start(nestedStart)
                    screens(nestedStart)
                    screenGroup(groupedSteps)
                }
            }
        }
    }

    @Test
    fun a_screen_registered_through_a_group_resolves_the_same_layouts() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val navModule = groupModule()
            val store = createStore {
                module(navModule)
                coroutineContext(StandardTestDispatcher(testScheduler))
            }
            store.navigation { navigateTo("wizard/grouped-one") }
            advanceUntilIdle()

            val entry = store.selectState<NavigationState>().first().currentEntry
            assertEquals(
                listOf("wizard"),
                findLayoutGraphsInHierarchy(
                    navModule.getGraphId(entry) ?: entry.route,
                    navModule.getGraphDefinitions()
                ).map { it.route },
                "a group only flattens screens into the graph it is called in, so the layout is " +
                    "resolved exactly as it is for screens declared one by one"
            )
        }

    @Test
    fun a_grouped_screen_inside_a_nested_graph_still_keeps_the_parent_layout() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val navModule = groupModule()
            val store = createStore {
                module(navModule)
                coroutineContext(StandardTestDispatcher(testScheduler))
            }
            store.navigation { navigateTo("wizard/grouped/grouped-two") }
            advanceUntilIdle()

            val entry = store.selectState<NavigationState>().first().currentEntry
            assertEquals(
                listOf("wizard"),
                findLayoutGraphsInHierarchy(
                    navModule.getGraphId(entry) ?: entry.route,
                    navModule.getGraphDefinitions()
                ).map { it.route },
                "membership by group does not change which graph a screen belongs to"
            )
        }
}
