import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTopPositionInRootIsEqualTo
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.waitUntilExactlyOneExists
import androidx.compose.ui.unit.dp
import io.github.syrou.reaktiv.compose.StoreProvider
import io.github.syrou.reaktiv.core.createStore
import io.github.syrou.reaktiv.navigation.createNavigationModule
import io.github.syrou.reaktiv.navigation.definition.DismissIndicatorPlacement
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.extension.navigateBack
import io.github.syrou.reaktiv.navigation.extension.navigation
import io.github.syrou.reaktiv.navigation.param.Params
import io.github.syrou.reaktiv.navigation.transition.NavTransition
import io.github.syrou.reaktiv.navigation.ui.NavigationRender
import kotlinx.coroutines.launch
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class ChromeIndicatorPlacementUiTest {

    private object OutsideChromeScreen : Screen {
        override val route = "outside"
        override val enterTransition = NavTransition.None
        override val exitTransition = NavTransition.None

        @Composable
        override fun Content(params: Params) {
            Text("Outside Body")
        }
    }

    private object ChromeStepScreen : Screen {
        override val route = "step"
        override val enterTransition = NavTransition.None
        override val exitTransition = NavTransition.None

        @Composable
        override fun Content(params: Params) {
            Text("Chrome Step Body")
        }
    }

    private object ChromeSheetScreen : Screen {
        override val route = "sheet"
        override val enterTransition = NavTransition.SlideUpBottom
        override val exitTransition = NavTransition.SlideOutBottom
        override val dismissIndicatorPlacement = DismissIndicatorPlacement.Surface

        @Composable
        override fun Content(params: Params) {
            Box(modifier = Modifier.fillMaxSize().testTag("chrome-sheet-body")) {
                Text("Chrome Sheet Body")
            }
        }
    }

    private fun module() = createNavigationModule {
        rootGraph {
            start(OutsideChromeScreen)
            screens(OutsideChromeScreen)
            graph("chrome") {
                start(ChromeStepScreen)
                screens(ChromeStepScreen, ChromeSheetScreen)
                layout { content ->
                    Column {
                        Text(text = "Chrome Header", modifier = Modifier.testTag("chrome-header"))
                        content()
                    }
                }
            }
        }
    }

    private val stripHeight = 28.dp

    private fun ComposeUiTest.assertSheetSitsUnderTheChromeWithItsOwnStrip() {
        val header = onNodeWithTag("chrome-header").getUnclippedBoundsInRoot()
        onNodeWithTag("chrome-header").assertTopPositionInRootIsEqualTo(0.dp)
        onNodeWithTag("chrome-sheet-body").assertTopPositionInRootIsEqualTo(header.bottom + stripHeight)
        onAllNodesWithTag("reaktiv-dismiss-indicator").assertCountEquals(1)
    }

    @Test
    fun the_handle_sits_in_the_same_place_however_the_sheet_was_reached() = runComposeUiTest {
        val store = createStore { module(module()) }
        setContent { StoreProvider(store) { NavigationRender() } }
        waitUntilExactlyOneExists(hasText("Outside Body"), timeoutMillis = UI_TEST_WAIT_MS)

        store.launch { store.navigation { navigateTo("chrome/sheet") } }
        awaitCurrentScreen(store, "chrome/sheet")
        waitUntilExactlyOneExists(hasText("Chrome Sheet Body"), timeoutMillis = UI_TEST_WAIT_MS)
        waitUntil(timeoutMillis = UI_TEST_WAIT_MS) {
            onAllNodesWithText("Outside Body").fetchSemanticsNodes().isEmpty()
        }
        waitForIdle()

        assertSheetSitsUnderTheChromeWithItsOwnStrip()

        store.launch { store.navigateBack() }
        awaitCurrentScreen(store, "outside")
        waitUntilExactlyOneExists(hasText("Outside Body"), timeoutMillis = UI_TEST_WAIT_MS)
        waitUntil(timeoutMillis = UI_TEST_WAIT_MS) {
            onAllNodesWithText("Chrome Sheet Body").fetchSemanticsNodes().isEmpty()
        }

        store.launch { store.navigation { navigateTo("chrome/step") } }
        awaitCurrentScreen(store, "chrome/step")
        waitUntilExactlyOneExists(hasText("Chrome Step Body"), timeoutMillis = UI_TEST_WAIT_MS)
        waitForIdle()

        onNodeWithTag("chrome-header").assertTopPositionInRootIsEqualTo(0.dp)

        store.launch { store.navigation { navigateTo("chrome/sheet") } }
        awaitCurrentScreen(store, "chrome/sheet")
        waitUntilExactlyOneExists(hasText("Chrome Sheet Body"), timeoutMillis = UI_TEST_WAIT_MS)
        waitUntil(timeoutMillis = UI_TEST_WAIT_MS) {
            onAllNodesWithText("Chrome Step Body").fetchSemanticsNodes().isEmpty()
        }
        waitForIdle()

        assertSheetSitsUnderTheChromeWithItsOwnStrip()
    }
}
