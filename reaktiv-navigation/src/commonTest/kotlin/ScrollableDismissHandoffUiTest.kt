import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.waitUntilExactlyOneExists
import io.github.syrou.reaktiv.compose.StoreProvider
import io.github.syrou.reaktiv.core.Middleware
import io.github.syrou.reaktiv.core.Store
import io.github.syrou.reaktiv.core.createStore
import io.github.syrou.reaktiv.navigation.NavigationAction
import io.github.syrou.reaktiv.navigation.extension.navigation
import io.github.syrou.reaktiv.navigation.ui.NavigationRender
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class ScrollableDismissHandoffUiTest {

    private class BackActionRecorder {
        val backActions = mutableListOf<NavigationAction.Back>()

        val middleware: Middleware = { action, _, _, updatedState ->
            if (action is NavigationAction.Back) {
                backActions.add(action)
            }
            updatedState(action)
        }
    }

    private fun buildStore(recorder: BackActionRecorder): Store = createStore {
        module(createUiTestModule())
        middlewares(recorder.middleware)
    }

    @Test
    fun dragOverScrollableContentAtTopHandsOffToDismiss() = runComposeUiTest {
        val recorder = BackActionRecorder()
        val store = buildStore(recorder)
        setContent {
            StoreProvider(store) {
                NavigationRender()
            }
        }
        waitUntilExactlyOneExists(hasText("UI Home"), timeoutMillis = UI_TEST_WAIT_MS)
        store.launch { store.navigation { navigateTo("ui-scroll-sheet") } }
        awaitCurrentScreen(store, "ui-scroll-sheet")
        waitUntilExactlyOneExists(hasText("Sheet Row 0"), timeoutMillis = UI_TEST_WAIT_MS)
        waitUntil(timeoutMillis = UI_TEST_WAIT_MS) { onAllNodesWithText("UI Home").fetchSemanticsNodes().isEmpty() }

        onRoot().performTouchInput {
            down(Offset(centerX, height * 0.3f))
            repeat(8) {
                moveBy(Offset(0f, height * 0.07f), delayMillis = 30)
            }
            moveBy(Offset(0f, 2f), delayMillis = 100)
            up()
        }
        awaitCurrentScreen(store, "ui-home")
        waitUntilExactlyOneExists(hasText("UI Home"), timeoutMillis = UI_TEST_WAIT_MS)
        waitUntil(timeoutMillis = UI_TEST_WAIT_MS) { onAllNodesWithText("Sheet Row 0").fetchSemanticsNodes().isEmpty() }
        assertEquals(1, recorder.backActions.size)
    }

    @Test
    fun dragOverScrolledContentScrollsInsteadOfDismissing() = runComposeUiTest {
        val recorder = BackActionRecorder()
        val store = buildStore(recorder)
        setContent {
            StoreProvider(store) {
                NavigationRender()
            }
        }
        waitUntilExactlyOneExists(hasText("UI Home"), timeoutMillis = UI_TEST_WAIT_MS)
        store.launch { store.navigation { navigateTo("ui-scroll-sheet") } }
        awaitCurrentScreen(store, "ui-scroll-sheet")
        waitUntilExactlyOneExists(hasText("Sheet Row 0"), timeoutMillis = UI_TEST_WAIT_MS)
        waitUntil(timeoutMillis = UI_TEST_WAIT_MS) { onAllNodesWithText("UI Home").fetchSemanticsNodes().isEmpty() }

        onRoot().performTouchInput {
            down(Offset(centerX, height * 0.7f))
            repeat(8) {
                moveBy(Offset(0f, -height * 0.07f), delayMillis = 30)
            }
            moveBy(Offset(0f, -2f), delayMillis = 100)
            up()
        }
        waitForIdle()
        assertEquals(0, recorder.backActions.size)

        onRoot().performTouchInput {
            down(Offset(centerX, height * 0.3f))
            moveBy(Offset(0f, 80f), delayMillis = 30)
            moveBy(Offset(0f, 80f), delayMillis = 30)
            moveBy(Offset(0f, 2f), delayMillis = 100)
            up()
        }
        waitForIdle()
        assertTrue(onAllNodesWithText("UI Home").fetchSemanticsNodes().isEmpty())
        onNodeWithTag("ui-scroll-sheet-list").assertExists()
        assertEquals(0, recorder.backActions.size)
    }
}
