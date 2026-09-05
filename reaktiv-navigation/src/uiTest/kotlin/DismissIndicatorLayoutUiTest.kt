import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTopPositionInRootIsEqualTo
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.waitUntilExactlyOneExists
import androidx.compose.ui.unit.dp
import io.github.syrou.reaktiv.compose.StoreProvider
import io.github.syrou.reaktiv.core.Store
import io.github.syrou.reaktiv.core.createStore
import io.github.syrou.reaktiv.navigation.createNavigationModule
import io.github.syrou.reaktiv.navigation.definition.Modal
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.extension.navigation
import io.github.syrou.reaktiv.navigation.param.Params
import io.github.syrou.reaktiv.navigation.transition.NavTransition
import io.github.syrou.reaktiv.navigation.ui.NavigationRender
import kotlinx.coroutines.launch
import kotlin.test.Test

/**
 * A sheet reserves the strip for its grab affordance because of what it is, not because of what
 * happens to be beneath it. The pill itself is only drawn when the drag can actually arm.
 */
@OptIn(ExperimentalTestApi::class)
class DismissIndicatorLayoutUiTest {

    private object UiClearSheetScreen : Screen {
        override val route = "ui-clear-sheet"
        override val enterTransition = NavTransition.SlideUpBottom
        override val exitTransition = NavTransition.SlideOutBottom
        override val dismissIndicatorColor = Color.Transparent
        override val dismissIndicatorBackground = Color.Transparent

        @Composable
        override fun Content(params: Params) {
            Box(modifier = Modifier.fillMaxSize().testTag("ui-clear-sheet-screen")) { Text("UI Clear Sheet") }
        }
    }

    private object UiAlertModal : Modal {
        override val route = "ui-alert"
        override val enterTransition = NavTransition.Fade
        override val exitTransition = NavTransition.Fade

        @Composable
        override fun Content(params: Params) {
            Box(modifier = Modifier.testTag("ui-alert-modal")) { Text("UI Alert") }
        }
    }

    private fun buildStore(): Store = createStore {
        module(createNavigationModule {
            rootGraph {
                start(UiHomeScreen)
                screens(UiHomeScreen, UiSheetScreen, UiClearSheetScreen)
                modals(UiAlertModal)
            }
        })
    }

    private val stripHeight = 28.dp

    @Test
    fun transparentIndicatorKeepsTheStripAndTheDragTarget() = runComposeUiTest {
        val store = buildStore()
        setContent {
            StoreProvider(store) {
                NavigationRender()
            }
        }
        waitUntilExactlyOneExists(hasText("UI Home"), timeoutMillis = UI_TEST_WAIT_MS)

        store.launch { store.navigation { navigateTo("ui-clear-sheet") } }
        awaitCurrentScreen(store, "ui-clear-sheet")
        waitUntilExactlyOneExists(hasText("UI Clear Sheet"), timeoutMillis = UI_TEST_WAIT_MS)
        waitUntil(timeoutMillis = UI_TEST_WAIT_MS) { onAllNodesWithText("UI Home").fetchSemanticsNodes().isEmpty() }

        onNodeWithTag("ui-clear-sheet-screen").assertTopPositionInRootIsEqualTo(stripHeight)
        waitUntilExactlyOneExists(hasTestTag("reaktiv-dismiss-indicator"), timeoutMillis = UI_TEST_WAIT_MS)
    }

    @Test
    fun sheetOverScreenReservesStripAndShowsPill() = runComposeUiTest {
        val store = buildStore()
        setContent {
            StoreProvider(store) {
                NavigationRender()
            }
        }
        waitUntilExactlyOneExists(hasText("UI Home"), timeoutMillis = UI_TEST_WAIT_MS)

        store.launch { store.navigation { navigateTo("ui-sheet") } }
        awaitCurrentScreen(store, "ui-sheet")
        waitUntilExactlyOneExists(hasText("UI Sheet"), timeoutMillis = UI_TEST_WAIT_MS)
        waitUntil(timeoutMillis = UI_TEST_WAIT_MS) { onAllNodesWithText("UI Home").fetchSemanticsNodes().isEmpty() }

        onNodeWithTag("ui-sheet-screen").assertTopPositionInRootIsEqualTo(stripHeight)
        waitUntilExactlyOneExists(hasTestTag("reaktiv-dismiss-indicator"), timeoutMillis = UI_TEST_WAIT_MS)
    }

    @Test
    fun sheetOverModalReservesTheSameStripWithoutPill() = runComposeUiTest {
        val store = buildStore()
        setContent {
            StoreProvider(store) {
                NavigationRender()
            }
        }
        waitUntilExactlyOneExists(hasText("UI Home"), timeoutMillis = UI_TEST_WAIT_MS)

        store.launch { store.navigation { navigateTo("ui-alert") } }
        awaitCurrentScreen(store, "ui-alert")
        waitUntilExactlyOneExists(hasText("UI Alert"), timeoutMillis = UI_TEST_WAIT_MS)

        store.launch { store.navigation { navigateTo("ui-sheet") } }
        awaitCurrentScreen(store, "ui-sheet")
        waitUntilExactlyOneExists(hasText("UI Sheet"), timeoutMillis = UI_TEST_WAIT_MS)
        waitUntil(timeoutMillis = UI_TEST_WAIT_MS) { onAllNodesWithText("UI Home").fetchSemanticsNodes().isEmpty() }

        onNodeWithTag("ui-sheet-screen").assertTopPositionInRootIsEqualTo(stripHeight)
        waitForIdle()
        onAllNodesWithTag("reaktiv-dismiss-indicator").assertCountEquals(0)
    }
}
