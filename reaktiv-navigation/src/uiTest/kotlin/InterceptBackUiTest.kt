import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.waitUntilExactlyOneExists
import androidx.compose.ui.unit.dp
import io.github.syrou.reaktiv.compose.StoreProvider
import io.github.syrou.reaktiv.core.Middleware
import io.github.syrou.reaktiv.core.Store
import io.github.syrou.reaktiv.core.createStore
import io.github.syrou.reaktiv.core.util.selectState
import io.github.syrou.reaktiv.navigation.NavigationAction
import io.github.syrou.reaktiv.navigation.NavigationModule
import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.createNavigationModule
import io.github.syrou.reaktiv.navigation.definition.Dismissal
import io.github.syrou.reaktiv.navigation.definition.Modal
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.extension.navigation
import io.github.syrou.reaktiv.navigation.param.Params
import io.github.syrou.reaktiv.navigation.transition.NavTransition
import io.github.syrou.reaktiv.navigation.ui.BackGesturePolicy
import io.github.syrou.reaktiv.navigation.ui.InteractiveTransitionController
import io.github.syrou.reaktiv.navigation.ui.InterceptBack
import io.github.syrou.reaktiv.navigation.ui.LocalBackGesturePolicy
import io.github.syrou.reaktiv.navigation.ui.LocalInteractiveTransitionController
import io.github.syrou.reaktiv.navigation.ui.NavigationRender
import io.github.syrou.reaktiv.navigation.ui.PlatformBackCoordinator
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class InterceptBackUiTest {

    private object Probe {
        var intercepting by mutableStateOf(true)
        var intercepted = 0
        var controller: InteractiveTransitionController? = null

        fun reset() {
            intercepting = true
            intercepted = 0
            controller = null
        }
    }

    private object HomeScreen : Screen {
        override val route = "intercept-home"
        override val enterTransition = NavTransition.IOSSlideIn
        override val exitTransition = NavTransition.IOSSlideOut

        @Composable
        override fun Content(params: Params) {
            Box(modifier = Modifier.fillMaxSize()) { Text("Home") }
        }
    }

    private object EditorScreen : Screen {
        override val route = "intercept-editor"
        override val enterTransition = NavTransition.IOSSlideIn
        override val exitTransition = NavTransition.IOSSlideOut

        @Composable
        override fun Content(params: Params) {
            Probe.controller = LocalInteractiveTransitionController.current
            InterceptBack(enabled = Probe.intercepting) { Probe.intercepted++ }
            Box(modifier = Modifier.fillMaxSize()) { Text("Editor") }
        }
    }

    private object SheetScreen : Screen {
        override val route = "intercept-sheet"
        override val enterTransition = NavTransition.SlideUpBottom
        override val exitTransition = NavTransition.SlideOutBottom

        @Composable
        override fun Content(params: Params) {
            Box(modifier = Modifier.fillMaxSize()) { Text("Sheet") }
        }
    }

    private abstract class SmallModal(override val route: String) : Modal {
        override val enterTransition = NavTransition.None
        override val exitTransition = NavTransition.None

        @Composable
        override fun Content(params: Params) {
            Box(modifier = Modifier.size(120.dp).background(Color.White)) { Text(route) }
        }
    }

    private object DefaultModal : SmallModal("intercept-default-modal")

    private object BlockingModal : SmallModal("intercept-blocking-modal") {
        override val dismissal = Dismissal.Blocking
    }

    private val appleEdge = BackGesturePolicy.Everywhere.copy(edgeSwipeRequestsBack = true)

    private class BackRecorder {
        val backs = mutableListOf<NavigationAction.Back>()

        val middleware: Middleware = { action, _, _, updatedState ->
            if (action is NavigationAction.Back) backs.add(action)
            updatedState(action)
        }
    }

    private class Harness(val store: Store, val navModule: NavigationModule, val recorder: BackRecorder)

    private fun ComposeUiTest.mount(policy: BackGesturePolicy): Harness {
        Probe.reset()
        val navModule = createNavigationModule {
            rootGraph {
                start(HomeScreen)
                screens(HomeScreen, EditorScreen, SheetScreen)
                modals(DefaultModal, BlockingModal)
            }
        }
        val recorder = BackRecorder()
        val store = createStore {
            module(navModule)
            middlewares(recorder.middleware)
        }
        setContent {
            StoreProvider(store) {
                CompositionLocalProvider(LocalBackGesturePolicy provides policy) {
                    NavigationRender()
                }
            }
        }
        waitUntilExactlyOneExists(hasText("Home"), timeoutMillis = UI_TEST_WAIT_MS)
        return Harness(store, navModule, recorder)
    }

    private fun ComposeUiTest.open(harness: Harness, route: String, text: String, coversHome: Boolean = true) {
        harness.store.launch { harness.store.navigation { navigateTo(route) } }
        awaitCurrentScreen(harness.store, route)
        waitUntilExactlyOneExists(hasText(text), timeoutMillis = UI_TEST_WAIT_MS)
        if (coversHome) {
            waitUntil(timeoutMillis = UI_TEST_WAIT_MS) { onAllNodesWithText("Home").fetchSemanticsNodes().isEmpty() }
        }
        waitForIdle()
    }

    private fun ComposeUiTest.openModal(harness: Harness, modal: Modal) {
        harness.store.launch { harness.store.navigation { navigateTo(modal) } }
        awaitCurrentScreen(harness.store, modal.route)
        waitUntilExactlyOneExists(hasText(modal.route), timeoutMillis = UI_TEST_WAIT_MS)
        waitForIdle()
    }

    private fun ComposeUiTest.dragFromEdge(): Boolean {
        onRoot().performTouchInput {
            down(Offset(10f, centerY))
            repeat(8) { moveBy(Offset(width * 0.07f, 0f), delayMillis = 30) }
        }
        val revealedMidDrag = onAllNodesWithText("Home").fetchSemanticsNodes().isNotEmpty()
        onRoot().performTouchInput {
            moveBy(Offset(2f, 0f), delayMillis = 100)
            up()
        }
        waitForIdle()
        return revealedMidDrag
    }

    private fun currentRoute(harness: Harness): String =
        runBlocking { harness.store.selectState<NavigationState>() }.value.currentEntry.navigatable.route

    @Test
    fun edgeSwipeGoesToTheInterceptorWithoutMovingTheScreen() = runComposeUiTest {
        val harness = mount(appleEdge)
        open(harness, EditorScreen.route, "Editor")

        val revealedMidDrag = dragFromEdge()

        assertFalse(revealedMidDrag)
        assertEquals(1, Probe.intercepted)
        assertTrue(harness.recorder.backs.isEmpty())
        assertEquals(EditorScreen.route, currentRoute(harness))
    }

    @Test
    fun interceptorStopsBackSwipesWhereTheEdgeDoesNotRequestBack() = runComposeUiTest {
        val harness = mount(BackGesturePolicy.Everywhere)
        open(harness, EditorScreen.route, "Editor")

        val revealedMidDrag = dragFromEdge()

        assertFalse(revealedMidDrag)
        assertEquals(0, Probe.intercepted)
        assertTrue(harness.recorder.backs.isEmpty())
        assertEquals(EditorScreen.route, currentRoute(harness))
    }

    @Test
    fun disabledInterceptorLeavesBackToTheScreen() = runComposeUiTest {
        val harness = mount(appleEdge)
        open(harness, EditorScreen.route, "Editor")
        Probe.intercepting = false
        waitForIdle()

        val revealedMidDrag = dragFromEdge()
        awaitCurrentScreen(harness.store, HomeScreen.route)

        assertTrue(revealedMidDrag)
        assertEquals(0, Probe.intercepted)
        assertEquals(1, harness.recorder.backs.size)
    }

    @Test
    fun platformBackRunsTheInterceptorAndOtherwiseGoesBack() = runComposeUiTest {
        val harness = mount(BackGesturePolicy.Everywhere)
        open(harness, EditorScreen.route, "Editor")
        val controller = checkNotNull(Probe.controller)
        val navigationState = runBlocking { harness.store.selectState<NavigationState>() }
        val coordinator = PlatformBackCoordinator(harness.store, harness.navModule, controller) {
            navigationState.value
        }

        assertFalse(coordinator.startScrub())
        runBlocking { coordinator.commit() }
        waitForIdle()

        assertEquals(1, Probe.intercepted)
        assertTrue(harness.recorder.backs.isEmpty())

        Probe.intercepting = false
        waitForIdle()
        runBlocking { coordinator.commit() }
        awaitCurrentScreen(harness.store, HomeScreen.route)

        assertEquals(1, Probe.intercepted)
        assertEquals(1, harness.recorder.backs.size)
    }

    @Test
    fun interceptorUnderAModalWaitsUntilItsScreenIsOnTop() = runComposeUiTest {
        val harness = mount(appleEdge)
        open(harness, EditorScreen.route, "Editor")
        openModal(harness, DefaultModal)

        dragFromEdge()
        awaitCurrentScreen(harness.store, EditorScreen.route)

        assertEquals(0, Probe.intercepted)
        assertEquals(1, harness.recorder.backs.size)

        dragFromEdge()

        assertEquals(1, Probe.intercepted)
        assertEquals(1, harness.recorder.backs.size)
    }

    @Test
    fun edgeSwipeOnAScreenThatArrivedVerticallyGoesBack() = runComposeUiTest {
        val harness = mount(appleEdge)
        open(harness, SheetScreen.route, "Sheet", coversHome = false)

        dragFromEdge()
        awaitCurrentScreen(harness.store, HomeScreen.route)

        assertEquals(1, harness.recorder.backs.size)
    }

    @Test
    fun edgeSwipeLeavesABlockingModalOpen() = runComposeUiTest {
        val harness = mount(appleEdge)
        openModal(harness, BlockingModal)

        dragFromEdge()

        assertTrue(harness.recorder.backs.isEmpty())
        assertEquals(BlockingModal.route, currentRoute(harness))
    }
}
