import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
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
import androidx.compose.ui.unit.dp
import io.github.syrou.reaktiv.navigation.NavigationAction
import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.createNavigationModule
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.extension.navigation
import io.github.syrou.reaktiv.navigation.param.Params
import io.github.syrou.reaktiv.navigation.transition.NavTransition
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
        waitUntilExactlyOneExists(hasText("UI Home"), timeoutMillis = UI_TEST_WAIT_MS)
        store.launch { store.navigation { navigateTo("ui-detail") } }
        awaitCurrentScreen(store, "ui-detail")
        waitUntilExactlyOneExists(hasText("UI Detail"), timeoutMillis = UI_TEST_WAIT_MS)
        waitUntil(timeoutMillis = UI_TEST_WAIT_MS) { onAllNodesWithText("UI Home").fetchSemanticsNodes().isEmpty() }

        onRoot().performTouchInput {
            down(Offset(10f, centerY))
            moveBy(Offset(60f, 0f))
            moveBy(Offset(80f, 0f))
        }
        waitUntilExactlyOneExists(hasText("UI Home"), timeoutMillis = UI_TEST_WAIT_MS)
        onNodeWithText("UI Detail").assertExists()
        assertEquals(0, recorder.backActions.size)

        onRoot().performTouchInput {
            moveBy(Offset(2f, 0f), delayMillis = 100)
            moveBy(Offset(2f, 0f), delayMillis = 100)
            up()
        }
        waitUntil(timeoutMillis = UI_TEST_WAIT_MS) { onAllNodesWithText("UI Home").fetchSemanticsNodes().isEmpty() }
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
        waitUntilExactlyOneExists(hasText("UI Home"), timeoutMillis = UI_TEST_WAIT_MS)
        store.launch { store.navigation { navigateTo("ui-detail") } }
        awaitCurrentScreen(store, "ui-detail")
        waitUntilExactlyOneExists(hasText("UI Detail"), timeoutMillis = UI_TEST_WAIT_MS)
        waitUntil(timeoutMillis = UI_TEST_WAIT_MS) { onAllNodesWithText("UI Home").fetchSemanticsNodes().isEmpty() }

        onRoot().performTouchInput {
            down(Offset(10f, centerY))
            repeat(8) {
                moveBy(Offset(width * 0.08f, 0f), delayMillis = 30)
            }
            moveBy(Offset(2f, 0f), delayMillis = 100)
            up()
        }
        awaitCurrentScreen(store, "ui-home")
        waitUntilExactlyOneExists(hasText("UI Home"), timeoutMillis = UI_TEST_WAIT_MS)
        waitUntil(timeoutMillis = UI_TEST_WAIT_MS) { onAllNodesWithText("UI Detail").fetchSemanticsNodes().isEmpty() }
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
        waitUntilExactlyOneExists(hasText("UI Home"), timeoutMillis = UI_TEST_WAIT_MS)
        store.launch { store.navigation { navigateTo("ui-detail") } }
        awaitCurrentScreen(store, "ui-detail")
        waitUntilExactlyOneExists(hasText("UI Detail"), timeoutMillis = UI_TEST_WAIT_MS)
        waitUntil(timeoutMillis = UI_TEST_WAIT_MS) { onAllNodesWithText("UI Home").fetchSemanticsNodes().isEmpty() }

        onRoot().performTouchInput {
            swipe(
                start = Offset(10f, centerY),
                end = Offset(width * 0.2f, centerY),
                durationMillis = 40
            )
        }
        waitUntilExactlyOneExists(hasText("UI Home"), timeoutMillis = UI_TEST_WAIT_MS)
        waitUntil(timeoutMillis = UI_TEST_WAIT_MS) { onAllNodesWithText("UI Detail").fetchSemanticsNodes().isEmpty() }
        assertEquals(1, recorder.backActions.size)
    }

    private object UiHorizontalPagerScreen : Screen {
        override val route = "ui-h-pager"
        override val enterTransition = NavTransition.None
        override val exitTransition = NavTransition.None

        @Composable
        override fun Content(params: Params) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .horizontalScroll(rememberScrollState())
                    .testTag("h-pager")
            ) {
                repeat(20) { index ->
                    Text(
                        text = "Page $index",
                        modifier = Modifier.width(200.dp)
                    )
                }
            }
        }
    }

    @Test
    fun edgeSwipeWinsOverHorizontallyScrollableContent() = runComposeUiTest {
        val recorder = BackActionRecorder()
        val store = createStore {
            module(createNavigationModule {
                rootGraph {
                    start(UiPagerHostScreen)
                    screens(UiPagerHostScreen, UiHorizontalPagerScreen)
                }
            })
            middlewares(recorder.middleware)
        }
        setContent {
            StoreProvider(store) {
                NavigationRender()
            }
        }
        waitUntilExactlyOneExists(hasText("Pager Host"), timeoutMillis = UI_TEST_WAIT_MS)
        store.launch { store.navigation { navigateTo("ui-h-pager") } }
        awaitCurrentScreen(store, "ui-h-pager")
        waitUntilExactlyOneExists(hasText("Page 0"), timeoutMillis = UI_TEST_WAIT_MS)
        waitUntil(timeoutMillis = UI_TEST_WAIT_MS) { onAllNodesWithText("Pager Host").fetchSemanticsNodes().isEmpty() }

        onRoot().performTouchInput {
            down(Offset(10f, centerY))
            repeat(8) {
                moveBy(Offset(width * 0.08f, 0f), delayMillis = 30)
            }
            moveBy(Offset(2f, 0f), delayMillis = 100)
            up()
        }
        awaitCurrentScreen(store, "ui-pager-host")
        waitUntilExactlyOneExists(hasText("Pager Host"), timeoutMillis = UI_TEST_WAIT_MS)
        waitUntil(timeoutMillis = UI_TEST_WAIT_MS) { onAllNodesWithText("Page 0").fetchSemanticsNodes().isEmpty() }
        assertEquals(1, recorder.backActions.size, "Edge swipe must win over a horizontally scrollable child")

        store.launch { store.navigation { navigateTo("ui-h-pager") } }
        awaitCurrentScreen(store, "ui-h-pager")
        waitUntilExactlyOneExists(hasText("Page 0"), timeoutMillis = UI_TEST_WAIT_MS)
        onRoot().performTouchInput {
            down(Offset(centerX, centerY))
            repeat(6) {
                moveBy(Offset(-width * 0.08f, 0f), delayMillis = 30)
            }
            up()
        }
        waitUntilExactlyOneExists(hasText("Page 12"), timeoutMillis = UI_TEST_WAIT_MS)
        assertEquals(1, recorder.backActions.size, "Mid-content horizontal scrolling must remain untouched")
    }

    private object UiPagerHostScreen : Screen {
        override val route = "ui-pager-host"
        override val enterTransition = NavTransition.None
        override val exitTransition = NavTransition.None

        @Composable
        override fun Content(params: Params) {
            Text("Pager Host")
        }
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
        waitUntilExactlyOneExists(hasText("UI Home"), timeoutMillis = UI_TEST_WAIT_MS)
        store.launch { store.navigation { navigateTo("ui-detail") } }
        awaitCurrentScreen(store, "ui-detail")
        waitUntilExactlyOneExists(hasText("UI Detail"), timeoutMillis = UI_TEST_WAIT_MS)
        waitUntil(timeoutMillis = UI_TEST_WAIT_MS) { onAllNodesWithText("UI Home").fetchSemanticsNodes().isEmpty() }

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
        waitUntilExactlyOneExists(hasText("UI Home"), timeoutMillis = UI_TEST_WAIT_MS)
        store.launch { store.navigation { navigateTo("ui-locked") } }
        awaitCurrentScreen(store, "ui-locked")
        waitUntilExactlyOneExists(hasText("UI Locked"), timeoutMillis = UI_TEST_WAIT_MS)
        waitUntil(timeoutMillis = UI_TEST_WAIT_MS) { onAllNodesWithText("UI Home").fetchSemanticsNodes().isEmpty() }

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
        waitUntilExactlyOneExists(hasText("UI Home"), timeoutMillis = UI_TEST_WAIT_MS)
        store.launch { store.navigation { navigateTo("ui-detail") } }
        awaitCurrentScreen(store, "ui-detail")
        waitUntilExactlyOneExists(hasText("UI Detail"), timeoutMillis = UI_TEST_WAIT_MS)
        waitUntil(timeoutMillis = UI_TEST_WAIT_MS) { onAllNodesWithText("UI Home").fetchSemanticsNodes().isEmpty() }

        onNodeWithTag("ui-detail-counter").performClick()
        onNodeWithTag("ui-detail-counter").performClick()
        waitUntilExactlyOneExists(hasText("Count: 2"), timeoutMillis = UI_TEST_WAIT_MS)

        onRoot().performTouchInput {
            down(Offset(10f, centerY))
            moveBy(Offset(60f, 0f))
            moveBy(Offset(80f, 0f))
            moveBy(Offset(2f, 0f), delayMillis = 100)
            moveBy(Offset(2f, 0f), delayMillis = 100)
            up()
        }
        waitUntil(timeoutMillis = UI_TEST_WAIT_MS) { onAllNodesWithText("UI Home").fetchSemanticsNodes().isEmpty() }
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
        waitUntilExactlyOneExists(hasText("UI Home"), timeoutMillis = UI_TEST_WAIT_MS)
        store.launch { store.navigation { navigateTo("ui-detail") } }
        awaitCurrentScreen(store, "ui-detail")
        waitUntilExactlyOneExists(hasText("UI Detail"), timeoutMillis = UI_TEST_WAIT_MS)
        waitUntil(timeoutMillis = UI_TEST_WAIT_MS) { onAllNodesWithText("UI Home").fetchSemanticsNodes().isEmpty() }

        val evaluatingSeen = store.launch {
            store.selectState<NavigationState>().first { it.isEvaluatingNavigation }
        }
        store.dispatch(NavigationAction.SetEvaluating(true))
        waitUntil(timeoutMillis = UI_TEST_WAIT_MS) { evaluatingSeen.isCompleted }

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
