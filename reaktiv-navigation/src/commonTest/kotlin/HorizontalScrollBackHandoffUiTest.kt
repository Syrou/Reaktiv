import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
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
class HorizontalScrollBackHandoffUiTest {

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
    fun edgeDownOverHorizontalListAtStartHandsOffToBack() = runComposeUiTest {
        val recorder = BackActionRecorder()
        val store = buildStore(recorder)
        setContent {
            StoreProvider(store) {
                NavigationRender()
            }
        }
        waitUntilExactlyOneExists(hasText("UI Home"), timeoutMillis = 5000)
        store.launch { store.navigation { navigateTo("ui-hscroll") } }
        waitUntilExactlyOneExists(hasText("Col 0"), timeoutMillis = 5000)
        waitUntil(timeoutMillis = 5000) { onAllNodesWithText("UI Home").fetchSemanticsNodes().isEmpty() }

        onRoot().performTouchInput {
            down(Offset(10f, centerY))
            repeat(8) {
                moveBy(Offset(width * 0.08f, 0f), delayMillis = 30)
            }
            moveBy(Offset(2f, 0f), delayMillis = 100)
            up()
        }
        waitUntilExactlyOneExists(hasText("UI Home"), timeoutMillis = 5000)
        waitUntil(timeoutMillis = 5000) { onAllNodesWithText("Col 0").fetchSemanticsNodes().isEmpty() }
        assertEquals(1, recorder.backActions.size)
    }

    @Test
    fun centerDownOverHorizontalListAtStartAlsoHandsOffToBack() = runComposeUiTest {
        val recorder = BackActionRecorder()
        val store = buildStore(recorder)
        setContent {
            StoreProvider(store) {
                NavigationRender()
            }
        }
        waitUntilExactlyOneExists(hasText("UI Home"), timeoutMillis = 5000)
        store.launch { store.navigation { navigateTo("ui-hscroll") } }
        waitUntilExactlyOneExists(hasText("Col 0"), timeoutMillis = 5000)
        waitUntil(timeoutMillis = 5000) { onAllNodesWithText("UI Home").fetchSemanticsNodes().isEmpty() }

        onRoot().performTouchInput {
            down(Offset(centerX, centerY))
            repeat(8) {
                moveBy(Offset(width * 0.08f, 0f), delayMillis = 30)
            }
            moveBy(Offset(2f, 0f), delayMillis = 100)
            up()
        }
        waitUntilExactlyOneExists(hasText("UI Home"), timeoutMillis = 5000)
        waitUntil(timeoutMillis = 5000) { onAllNodesWithText("Col 0").fetchSemanticsNodes().isEmpty() }
        assertEquals(1, recorder.backActions.size)
    }

    @Test
    fun dragOverScrolledForwardListScrollsInsteadOfPopping() = runComposeUiTest {
        val recorder = BackActionRecorder()
        val store = buildStore(recorder)
        setContent {
            StoreProvider(store) {
                NavigationRender()
            }
        }
        waitUntilExactlyOneExists(hasText("UI Home"), timeoutMillis = 5000)
        store.launch { store.navigation { navigateTo("ui-hscroll") } }
        waitUntilExactlyOneExists(hasText("Col 0"), timeoutMillis = 5000)
        waitUntil(timeoutMillis = 5000) { onAllNodesWithText("UI Home").fetchSemanticsNodes().isEmpty() }

        onRoot().performTouchInput {
            down(Offset(centerX, centerY))
            repeat(8) {
                moveBy(Offset(-width * 0.07f, 0f), delayMillis = 30)
            }
            moveBy(Offset(-2f, 0f), delayMillis = 100)
            up()
        }
        waitForIdle()
        assertEquals(0, recorder.backActions.size)

        onRoot().performTouchInput {
            down(Offset(centerX, centerY))
            moveBy(Offset(80f, 0f), delayMillis = 30)
            moveBy(Offset(80f, 0f), delayMillis = 30)
            moveBy(Offset(2f, 0f), delayMillis = 100)
            up()
        }
        waitForIdle()
        assertTrue(onAllNodesWithText("UI Home").fetchSemanticsNodes().isEmpty())
        onNodeWithTag("ui-hscroll-row").assertExists()
        assertEquals(0, recorder.backActions.size)
    }
}
