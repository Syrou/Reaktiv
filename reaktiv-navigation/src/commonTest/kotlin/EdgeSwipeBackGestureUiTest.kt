import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.waitUntilExactlyOneExists
import io.github.syrou.reaktiv.compose.StoreProvider
import io.github.syrou.reaktiv.core.Middleware
import io.github.syrou.reaktiv.core.Store
import io.github.syrou.reaktiv.core.createStore
import io.github.syrou.reaktiv.navigation.NavigationAction
import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.extension.navigation
import io.github.syrou.reaktiv.navigation.ui.NavigationRender
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class EdgeSwipeBackGestureUiTest {

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
    fun midScrubComposesRevealedScreenWithoutDispatchingAndCancelRestores() = runComposeUiTest {
        val recorder = BackActionRecorder()
        val store = buildStore(recorder)
        setContent {
            StoreProvider(store) {
                NavigationRender()
            }
        }
        waitUntilExactlyOneExists(hasText("UI Home"), timeoutMillis = 5000)
        store.launch { store.navigation { navigateTo("ui-detail") } }
        waitUntilExactlyOneExists(hasText("UI Detail"), timeoutMillis = 5000)
        waitUntil(timeoutMillis = 5000) { onAllNodesWithText("UI Home").fetchSemanticsNodes().isEmpty() }

        onRoot().performTouchInput {
            down(Offset(10f, centerY))
            moveBy(Offset(60f, 0f))
            moveBy(Offset(80f, 0f))
        }
        waitUntilExactlyOneExists(hasText("UI Home"), timeoutMillis = 5000)
        onNodeWithText("UI Detail").assertExists()
        assertEquals(0, recorder.backActions.size)

        onRoot().performTouchInput {
            moveBy(Offset(2f, 0f), delayMillis = 100)
            moveBy(Offset(2f, 0f), delayMillis = 100)
            up()
        }
        waitUntil(timeoutMillis = 5000) { onAllNodesWithText("UI Home").fetchSemanticsNodes().isEmpty() }
        onNodeWithText("UI Detail").assertExists()
        assertEquals(0, recorder.backActions.size)
    }

    @Test
    fun dragPastThresholdCommitsExactlyOneBack() = runComposeUiTest {
        val recorder = BackActionRecorder()
        val store = buildStore(recorder)
        setContent {
            StoreProvider(store) {
                NavigationRender()
            }
        }
        waitUntilExactlyOneExists(hasText("UI Home"), timeoutMillis = 5000)
        store.launch { store.navigation { navigateTo("ui-detail") } }
        waitUntilExactlyOneExists(hasText("UI Detail"), timeoutMillis = 5000)
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
        waitUntil(timeoutMillis = 5000) { onAllNodesWithText("UI Detail").fetchSemanticsNodes().isEmpty() }
        assertEquals(1, recorder.backActions.size)
    }

    @Test
    fun fastFlingCommitsDespiteLowProgress() = runComposeUiTest {
        val recorder = BackActionRecorder()
        val store = buildStore(recorder)
        setContent {
            StoreProvider(store) {
                NavigationRender()
            }
        }
        waitUntilExactlyOneExists(hasText("UI Home"), timeoutMillis = 5000)
        store.launch { store.navigation { navigateTo("ui-detail") } }
        waitUntilExactlyOneExists(hasText("UI Detail"), timeoutMillis = 5000)
        waitUntil(timeoutMillis = 5000) { onAllNodesWithText("UI Home").fetchSemanticsNodes().isEmpty() }

        onRoot().performTouchInput {
            swipe(
                start = Offset(10f, centerY),
                end = Offset(width * 0.2f, centerY),
                durationMillis = 40
            )
        }
        waitUntilExactlyOneExists(hasText("UI Home"), timeoutMillis = 5000)
        waitUntil(timeoutMillis = 5000) { onAllNodesWithText("UI Detail").fetchSemanticsNodes().isEmpty() }
        assertEquals(1, recorder.backActions.size)
    }

    @Test
    fun dragStartingAwayFromEdgeIsInert() = runComposeUiTest {
        val recorder = BackActionRecorder()
        val store = buildStore(recorder)
        setContent {
            StoreProvider(store) {
                NavigationRender()
            }
        }
        waitUntilExactlyOneExists(hasText("UI Home"), timeoutMillis = 5000)
        store.launch { store.navigation { navigateTo("ui-detail") } }
        waitUntilExactlyOneExists(hasText("UI Detail"), timeoutMillis = 5000)
        waitUntil(timeoutMillis = 5000) { onAllNodesWithText("UI Home").fetchSemanticsNodes().isEmpty() }

        onRoot().performTouchInput {
            down(Offset(300f, centerY))
            moveBy(Offset(200f, 0f))
        }
        waitForIdle()
        assertTrue(onAllNodesWithText("UI Home").fetchSemanticsNodes().isEmpty())
        onRoot().performTouchInput { up() }
        waitForIdle()
        onNodeWithText("UI Detail").assertExists()
        assertEquals(0, recorder.backActions.size)
    }

    @Test
    fun optedOutScreenIgnoresEdgeSwipe() = runComposeUiTest {
        val recorder = BackActionRecorder()
        val store = buildStore(recorder)
        setContent {
            StoreProvider(store) {
                NavigationRender()
            }
        }
        waitUntilExactlyOneExists(hasText("UI Home"), timeoutMillis = 5000)
        store.launch { store.navigation { navigateTo("ui-locked") } }
        waitUntilExactlyOneExists(hasText("UI Locked"), timeoutMillis = 5000)
        waitUntil(timeoutMillis = 5000) { onAllNodesWithText("UI Home").fetchSemanticsNodes().isEmpty() }

        onRoot().performTouchInput {
            down(Offset(10f, centerY))
            repeat(6) {
                moveBy(Offset(width * 0.1f, 0f), delayMillis = 30)
            }
            up()
        }
        waitForIdle()
        onNodeWithText("UI Locked").assertExists()
        assertTrue(onAllNodesWithText("UI Home").fetchSemanticsNodes().isEmpty())
        assertEquals(0, recorder.backActions.size)
    }

    @Test
    fun cancelledSwipePreservesTopScreenState() = runComposeUiTest {
        val recorder = BackActionRecorder()
        val store = buildStore(recorder)
        setContent {
            StoreProvider(store) {
                NavigationRender()
            }
        }
        waitUntilExactlyOneExists(hasText("UI Home"), timeoutMillis = 5000)
        store.launch { store.navigation { navigateTo("ui-detail") } }
        waitUntilExactlyOneExists(hasText("UI Detail"), timeoutMillis = 5000)
        waitUntil(timeoutMillis = 5000) { onAllNodesWithText("UI Home").fetchSemanticsNodes().isEmpty() }

        onNodeWithTag("ui-detail-counter").performClick()
        onNodeWithTag("ui-detail-counter").performClick()
        waitUntilExactlyOneExists(hasText("Count: 2"), timeoutMillis = 5000)

        onRoot().performTouchInput {
            down(Offset(10f, centerY))
            moveBy(Offset(60f, 0f))
            moveBy(Offset(80f, 0f))
            moveBy(Offset(2f, 0f), delayMillis = 100)
            moveBy(Offset(2f, 0f), delayMillis = 100)
            up()
        }
        waitUntil(timeoutMillis = 5000) { onAllNodesWithText("UI Home").fetchSemanticsNodes().isEmpty() }
        onNodeWithText("Count: 2").assertExists()
        assertEquals(0, recorder.backActions.size)
    }

    @Test
    fun evaluationOverlayBlocksEdgeSwipe() = runComposeUiTest {
        val recorder = BackActionRecorder()
        val store = buildStore(recorder)
        setContent {
            StoreProvider(store) {
                NavigationRender()
            }
        }
        waitUntilExactlyOneExists(hasText("UI Home"), timeoutMillis = 5000)
        store.launch { store.navigation { navigateTo("ui-detail") } }
        waitUntilExactlyOneExists(hasText("UI Detail"), timeoutMillis = 5000)
        waitUntil(timeoutMillis = 5000) { onAllNodesWithText("UI Home").fetchSemanticsNodes().isEmpty() }

        val evaluatingSeen = store.launch {
            store.selectState<NavigationState>().first { it.isEvaluatingNavigation }
        }
        store.dispatch(NavigationAction.SetEvaluating(true))
        waitUntil(timeoutMillis = 5000) { evaluatingSeen.isCompleted }

        onRoot().performTouchInput {
            down(Offset(10f, centerY))
            repeat(6) {
                moveBy(Offset(width * 0.1f, 0f), delayMillis = 30)
            }
            up()
        }
        waitForIdle()
        onNodeWithText("UI Detail").assertExists()
        assertEquals(0, recorder.backActions.size)
    }
}
