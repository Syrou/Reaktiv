import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.waitUntilExactlyOneExists
import io.github.syrou.reaktiv.compose.StoreProvider
import io.github.syrou.reaktiv.core.createStore
import io.github.syrou.reaktiv.navigation.createNavigationModule
import io.github.syrou.reaktiv.navigation.definition.Graph
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.extension.navigation
import io.github.syrou.reaktiv.navigation.param.Params
import io.github.syrou.reaktiv.navigation.transition.NavTransition
import io.github.syrou.reaktiv.navigation.ui.NavigationRender
import kotlinx.coroutines.launch
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

private val chromeTops = mutableListOf<Float>()

/**
 * A graph presented as a sheet is dragged away whole, so its grab affordance sits above the chrome
 * the graph draws. Stepping sideways inside the graph changes nothing about what a drag would take
 * away, so the affordance may not move while the step animates.
 */
@OptIn(ExperimentalTestApi::class)
class SheetChromeIndicatorUiTest {

    private object SheetGraph : Graph {
        override val route = "sheet"
        override val enterTransition = NavTransition.SlideUpBottom
        override val exitTransition = NavTransition.SlideOutBottom
    }

    private object HomeScreen : Screen {
        override val route = "home"
        override val enterTransition = NavTransition.None
        override val exitTransition = NavTransition.None

        @Composable
        override fun Content(params: Params) {
            Text("Home Body")
        }
    }

    private object StepOneScreen : Screen {
        override val route = "step-one"
        override val enterTransition = NavTransition.SlideInRight
        override val exitTransition = NavTransition.SlideOutLeft

        @Composable
        override fun Content(params: Params) {
            Text("Step One Body")
        }
    }

    private object StepTwoScreen : Screen {
        override val route = "step-two"
        override val enterTransition = NavTransition.SlideInRight
        override val exitTransition = NavTransition.SlideOutLeft

        @Composable
        override fun Content(params: Params) {
            Text("Step Two Body")
        }
    }

    private fun module() = createNavigationModule {
        rootGraph {
            start(HomeScreen)
            screens(HomeScreen)
            graph(SheetGraph) {
                start(StepOneScreen)
                screens(StepOneScreen, StepTwoScreen)
                layout { content ->
                    Column {
                        Text(
                            text = "Sheet Chrome",
                            modifier = Modifier.onGloballyPositioned { coordinates ->
                                chromeTops += coordinates.positionInRoot().y
                            }
                        )
                        content()
                    }
                }
            }
        }
    }

    @BeforeTest
    fun resetChromeTops() {
        chromeTops.clear()
    }

    @Test
    fun the_grab_affordance_stays_above_the_sheet_chrome_while_a_step_animates() = runComposeUiTest {
        val store = createStore { module(module()) }
        setContent { StoreProvider(store) { NavigationRender() } }
        waitUntilExactlyOneExists(hasText("Home Body"), timeoutMillis = UI_TEST_WAIT_MS)

        store.launch { store.navigation { navigateTo("sheet/step-one") } }
        awaitCurrentScreen(store, "step-one")
        waitUntilExactlyOneExists(hasText("Step One Body"), timeoutMillis = UI_TEST_WAIT_MS)
        waitUntil(timeoutMillis = UI_TEST_WAIT_MS) {
            onAllNodesWithText("Home Body").fetchSemanticsNodes().isEmpty()
        }
        waitForIdle()

        val settled = chromeTops.last()
        chromeTops.clear()

        store.launch { store.navigation { navigateTo("sheet/step-two") } }
        awaitCurrentScreen(store, "step-two")
        waitUntilExactlyOneExists(hasText("Step Two Body"), timeoutMillis = UI_TEST_WAIT_MS)
        waitUntil(timeoutMillis = UI_TEST_WAIT_MS) {
            onAllNodesWithText("Step One Body").fetchSemanticsNodes().isEmpty()
        }
        waitForIdle()

        // The strip the affordance lives in is what pushes the chrome down. Every measurement taken
        // across the step transition must report the chrome in the same place, or the affordance
        // dropped underneath it and came back.
        assertEquals(
            listOf(settled),
            chromeTops.distinct(),
            "the sheet chrome moved while a step animated inside the sheet"
        )
    }
}
