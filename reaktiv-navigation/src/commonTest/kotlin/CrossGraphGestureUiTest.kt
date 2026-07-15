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

@OptIn(ExperimentalTestApi::class)
class CrossGraphGestureUiTest {

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
        module(createUiGraphTestModule())
        middlewares(recorder.middleware)
    }

    @Test
    fun edgeSwipeArmsAcrossGraphBoundaryAndShowsBothScreens() = runComposeUiTest {
        val recorder = BackActionRecorder()
        val store = buildStore(recorder)
        setContent {
            StoreProvider(store) {
                NavigationRender()
            }
        }
        waitUntilExactlyOneExists(hasText("UI Workspace"), timeoutMillis = 5000)
        store.launch { store.navigation { navigateTo("project-area/ui-project") } }
        waitUntilExactlyOneExists(hasText("UI Project"), timeoutMillis = 5000)
        waitUntil(timeoutMillis = 5000) { onAllNodesWithText("UI Workspace").fetchSemanticsNodes().isEmpty() }
        onNodeWithTag("project-chrome").assertExists()

        onRoot().performTouchInput {
            down(Offset(10f, centerY))
            moveBy(Offset(60f, 0f))
            moveBy(Offset(80f, 0f))
        }
        waitUntilExactlyOneExists(hasText("UI Workspace"), timeoutMillis = 5000)
        onNodeWithText("UI Project").assertExists()
        assertEquals(0, recorder.backActions.size)

        onRoot().performTouchInput {
            moveBy(Offset(2f, 0f), delayMillis = 100)
            moveBy(Offset(2f, 0f), delayMillis = 100)
            up()
        }
        waitUntil(timeoutMillis = 5000) { onAllNodesWithText("UI Workspace").fetchSemanticsNodes().isEmpty() }
        onNodeWithText("UI Project").assertExists()
        assertEquals(0, recorder.backActions.size)
    }

    @Test
    fun edgeSwipeCommitsAcrossGraphBoundary() = runComposeUiTest {
        val recorder = BackActionRecorder()
        val store = buildStore(recorder)
        setContent {
            StoreProvider(store) {
                NavigationRender()
            }
        }
        waitUntilExactlyOneExists(hasText("UI Workspace"), timeoutMillis = 5000)
        store.launch { store.navigation { navigateTo("project-area/ui-project") } }
        waitUntilExactlyOneExists(hasText("UI Project"), timeoutMillis = 5000)
        waitUntil(timeoutMillis = 5000) { onAllNodesWithText("UI Workspace").fetchSemanticsNodes().isEmpty() }

        onRoot().performTouchInput {
            down(Offset(10f, centerY))
            repeat(8) {
                moveBy(Offset(width * 0.08f, 0f), delayMillis = 30)
            }
            moveBy(Offset(2f, 0f), delayMillis = 100)
            up()
        }
        waitUntilExactlyOneExists(hasText("UI Workspace"), timeoutMillis = 5000)
        waitUntil(timeoutMillis = 5000) { onAllNodesWithText("UI Project").fetchSemanticsNodes().isEmpty() }
        waitUntil(timeoutMillis = 5000) {
            onAllNodesWithText("UI Workspace").fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(1, recorder.backActions.size)
    }
}
