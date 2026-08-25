import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
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
import kotlin.test.Test

/**
 * A layout belongs to the graph that declares it, so a graph nested inside that one is still
 * within its scope and the chrome has to stay on screen when you enter it.
 */
@OptIn(ExperimentalTestApi::class)
class NestedGraphLayoutUiTest {

    private fun screen(name: String, label: String) = object : Screen {
        override val route = name
        override val enterTransition = NavTransition.SlideInRight
        override val exitTransition = NavTransition.SlideOutLeft

        @Composable
        override fun Content(params: Params) {
            Text(label)
        }
    }

    private val home = screen("home", "Home")
    private val stepOne = screen("step-one", "Step One")
    private val nestedStart = screen("nested-start", "Nested Start")

    private object WizardGraph : Graph {
        override val route = "wizard"
        override val enterTransition = NavTransition.SlideUpBottom
    }

    private object NestedGraph : Graph {
        override val route = "extras"
        override val enterTransition = NavTransition.SlideInRight
    }

    private fun module(nestedHasLayout: Boolean) = createNavigationModule {
        rootGraph {
            start(home)
            screens(home)
            graph(WizardGraph) {
                start(stepOne)
                screens(stepOne)
                layout { content ->
                    Column {
                        Text("Wizard Chrome")
                        content()
                    }
                }
                graph(NestedGraph) {
                    start(nestedStart)
                    screens(nestedStart)
                    if (nestedHasLayout) {
                        layout { content ->
                            Column {
                                Text("Nested Chrome")
                                content()
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun wizard_chrome_survives_entering_a_plain_nested_graph() = runComposeUiTest {
        val store = createStore { module(module(nestedHasLayout = false)) }
        setContent { StoreProvider(store) { NavigationRender() } }

        store.launch { store.navigation { navigateTo("wizard") } }
        waitUntilExactlyOneExists(hasText("Wizard Chrome"), timeoutMillis = UI_TEST_WAIT_MS)

        store.launch { store.navigation { navigateTo("wizard/extras") } }
        waitUntilExactlyOneExists(hasText("Nested Start"), timeoutMillis = UI_TEST_WAIT_MS)

        onNodeWithText("Wizard Chrome").assertExists()
    }

    @Test
    fun wizard_chrome_survives_entering_a_nested_graph_that_has_its_own_layout() = runComposeUiTest {
        val store = createStore { module(module(nestedHasLayout = true)) }
        setContent { StoreProvider(store) { NavigationRender() } }

        store.launch { store.navigation { navigateTo("wizard") } }
        waitUntilExactlyOneExists(hasText("Wizard Chrome"), timeoutMillis = UI_TEST_WAIT_MS)

        store.launch { store.navigation { navigateTo("wizard/extras") } }
        waitUntilExactlyOneExists(hasText("Nested Chrome"), timeoutMillis = UI_TEST_WAIT_MS)

        onNodeWithText("Wizard Chrome").assertExists()
    }
}
