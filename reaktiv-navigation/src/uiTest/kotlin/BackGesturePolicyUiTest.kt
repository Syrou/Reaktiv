import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.waitUntilExactlyOneExists
import io.github.syrou.reaktiv.compose.StoreProvider
import io.github.syrou.reaktiv.core.Middleware
import io.github.syrou.reaktiv.core.Store
import io.github.syrou.reaktiv.core.createStore
import io.github.syrou.reaktiv.navigation.NavigationAction
import io.github.syrou.reaktiv.navigation.extension.navigation
import io.github.syrou.reaktiv.navigation.ui.BackGesturePolicy
import io.github.syrou.reaktiv.navigation.ui.LocalBackGesturePolicy
import io.github.syrou.reaktiv.navigation.ui.NavigationRender
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class BackGesturePolicyUiTest {

    private val webWithMouse = BackGesturePolicy(
        edgeSwipe = true,
        fullSurfaceSwipe = true,
        fullSurfaceSkipsEdge = false,
        mouseStartsBack = false
    )

    private val webWithTouch = BackGesturePolicy(
        edgeSwipe = false,
        fullSurfaceSwipe = true,
        fullSurfaceSkipsEdge = true,
        mouseStartsBack = false
    )

    private class BackRecorder {
        val backs = mutableListOf<NavigationAction.Back>()

        val middleware: Middleware = { action, _, _, updatedState ->
            if (action is NavigationAction.Back) backs.add(action)
            updatedState(action)
        }
    }

    private fun ComposeUiTest.onDetail(policy: BackGesturePolicy?, recorder: BackRecorder): Store {
        val store = createStore {
            module(createUiTestModule())
            middlewares(recorder.middleware)
        }
        setContent {
            StoreProvider(store) {
                CompositionLocalProvider(LocalBackGesturePolicy provides policy) {
                    NavigationRender()
                }
            }
        }
        waitUntilExactlyOneExists(hasText("UI Home"), timeoutMillis = UI_TEST_WAIT_MS)
        store.launch { store.navigation { navigateTo("ui-detail") } }
        awaitCurrentScreen(store, "ui-detail")
        waitUntilExactlyOneExists(hasText("UI Detail"), timeoutMillis = UI_TEST_WAIT_MS)
        waitUntil(timeoutMillis = UI_TEST_WAIT_MS) { onAllNodesWithText("UI Home").fetchSemanticsNodes().isEmpty() }
        return store
    }

    private fun ComposeUiTest.touchDragFrom(startFraction: Float) {
        onRoot().performTouchInput {
            down(Offset(if (startFraction == 0f) 10f else width * startFraction, centerY))
            repeat(8) { moveBy(Offset(width * 0.07f, 0f), delayMillis = 30) }
            moveBy(Offset(2f, 0f), delayMillis = 100)
            up()
        }
    }

    private fun ComposeUiTest.mouseDragFromEdge() {
        onRoot().performMouseInput {
            moveTo(Offset(10f, centerY))
            press()
            repeat(8) { moveBy(Offset(width * 0.08f, 0f), delayMillis = 30) }
            moveBy(Offset(2f, 0f), delayMillis = 100)
            release()
        }
    }

    private fun ComposeUiTest.assertStillOnDetail(recorder: BackRecorder) {
        waitForIdle()
        runBlocking { delay(300) }
        waitForIdle()
        assertEquals(0, recorder.backs.size)
        waitUntilExactlyOneExists(hasText("UI Detail"), timeoutMillis = UI_TEST_WAIT_MS)
    }

    @Test
    fun `on the web a mouse drag never goes back`() = runComposeUiTest {
        val recorder = BackRecorder()
        onDetail(webWithMouse, recorder)

        mouseDragFromEdge()

        assertStillOnDetail(recorder)
    }

    @Test
    fun `on a touch browser a drag from the screen edge is left to the browser`() = runComposeUiTest {
        val recorder = BackRecorder()
        onDetail(webWithTouch, recorder)

        touchDragFrom(0f)

        assertStillOnDetail(recorder)
    }

    @Test
    fun `on a touch browser a drag from inside the screen goes back`() = runComposeUiTest {
        val recorder = BackRecorder()
        val store = onDetail(webWithTouch, recorder)

        touchDragFrom(0.2f)

        awaitCurrentScreen(store, "ui-home")
        assertEquals(1, recorder.backs.size)
    }

    @Test
    fun `on the desktop a mouse drag from the edge still goes back`() = runComposeUiTest {
        val recorder = BackRecorder()
        val store = onDetail(null, recorder)

        mouseDragFromEdge()

        awaitCurrentScreen(store, "ui-home")
        assertEquals(1, recorder.backs.size)
    }
}
