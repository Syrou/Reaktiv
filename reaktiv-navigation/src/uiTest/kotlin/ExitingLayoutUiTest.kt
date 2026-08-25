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
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.extension.navigation
import io.github.syrou.reaktiv.navigation.param.Params
import io.github.syrou.reaktiv.navigation.transition.NavTransition
import io.github.syrou.reaktiv.navigation.ui.NavigationRender
import kotlinx.coroutines.launch
import kotlin.test.Test

/**
 * A screen on its way out is still on screen, so it has to keep the chrome it arrived with.
 *
 * Moving between two sibling graphs whose layouts differ is the case that exposes this: the
 * outgoing screen's layout is not shared with the arriving one, so if it is not rendered around the
 * exiting slot the screen loses its header mid transition and its content jumps to fill the space.
 */
@OptIn(ExperimentalTestApi::class)
class ExitingLayoutUiTest {

    private fun screen(name: String, label: String, exit: NavTransition) = object : Screen {
        override val route = name
        override val enterTransition = NavTransition.SlideInRight
        override val exitTransition = exit

        @Composable
        override fun Content(params: Params) {
            Text(label)
        }
    }

    private val fromScreen = screen("from", "From Body", NavTransition.None)
    private val toScreen = screen("to", "To Body", NavTransition.SlideOutLeft)

    private fun module() = createNavigationModule {
        rootGraph {
            start("first")
            graph("first") {
                start(fromScreen)
                screens(fromScreen)
                layout { content ->
                    Column {
                        Text("First Chrome")
                        content()
                    }
                }
            }
            graph("second") {
                start(toScreen)
                screens(toScreen)
                layout { content ->
                    Column {
                        Text("Second Chrome")
                        content()
                    }
                }
            }
        }
    }

    @Test
    fun the_outgoing_screen_keeps_its_chrome_while_it_is_still_on_screen() = runComposeUiTest {
        val store = createStore { module(module()) }
        setContent { StoreProvider(store) { NavigationRender() } }
        waitUntilExactlyOneExists(hasText("First Chrome"), timeoutMillis = UI_TEST_WAIT_MS)

        mainClock.autoAdvance = false
        store.launch { store.navigation { navigateTo("second") } }

        // Part way through, both surfaces are on screen and both should be intact.
        repeat(6) { mainClock.advanceTimeBy(16) }
        onNodeWithText("From Body").assertExists()
        onNodeWithText("First Chrome").assertExists()

        mainClock.autoAdvance = true
        waitUntilExactlyOneExists(hasText("Second Chrome"), timeoutMillis = UI_TEST_WAIT_MS)
    }
}
