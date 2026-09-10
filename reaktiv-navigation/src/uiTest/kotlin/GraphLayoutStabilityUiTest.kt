import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
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
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

private class ScreenRecorder {
    var mounts = 0
    var disposals = 0
    var firstTop: Float? = null

    fun reset() {
        mounts = 0
        disposals = 0
        firstTop = null
    }
}

@Composable
private fun RecordedBody(recorder: ScreenRecorder, label: String) {
    remember { recorder.mounts++ }
    DisposableEffect(Unit) {
        onDispose { recorder.disposals++ }
    }
    Text(
        text = label,
        modifier = Modifier.onGloballyPositioned { coordinates ->
            if (recorder.firstTop == null) {
                recorder.firstTop = coordinates.positionInRoot().y
            }
        }
    )
}

/**
 * A screen is composed once for as long as it is rendered. Anything else silently throws away the
 * state it remembered, runs its DisposableEffect to completion while it is still visible, and
 * restarts every LaunchedEffect it had running.
 */
@OptIn(ExperimentalTestApi::class)
class GraphLayoutStabilityUiTest {

    private object Recorders {
        val first = ScreenRecorder()
        val second = ScreenRecorder()

        fun reset() {
            first.reset()
            second.reset()
        }
    }

    private object HomeScreen : Screen {
        override val route = "home"
        override val enterTransition = NavTransition.SlideInRight
        override val exitTransition = NavTransition.SlideOutLeft

        @Composable
        override fun Content(params: Params) {
            Text("Home Body")
        }
    }

    private object FlowFirstScreen : Screen {
        override val route = "flow-first"
        override val enterTransition = NavTransition.SlideInRight
        override val exitTransition = NavTransition.SlideOutLeft

        @Composable
        override fun Content(params: Params) {
            RecordedBody(Recorders.first, "Flow First Body")
        }
    }

    private object FlowSecondScreen : Screen {
        override val route = "flow-second"
        override val enterTransition = NavTransition.SlideInRight
        override val exitTransition = NavTransition.SlideOutLeft

        @Composable
        override fun Content(params: Params) {
            RecordedBody(Recorders.second, "Flow Second Body")
        }
    }

    private fun module() = createNavigationModule {
        rootGraph {
            start(HomeScreen)
            screens(HomeScreen)
            graph("flow") {
                start(FlowFirstScreen)
                screens(FlowFirstScreen, FlowSecondScreen)
                layout { content ->
                    Column {
                        Text("Flow Chrome")
                        content()
                    }
                }
            }
        }
    }

    @BeforeTest
    fun resetRecorders() {
        Recorders.reset()
    }

    @Test
    fun a_screen_is_not_remounted_when_its_graph_layout_stops_being_shared() = runComposeUiTest {
        val store = createStore { module(module()) }
        setContent { StoreProvider(store) { NavigationRender() } }
        waitUntilExactlyOneExists(hasText("Home Body"), timeoutMillis = UI_TEST_WAIT_MS)

        store.launch { store.navigation { navigateTo("flow/flow-first") } }
        awaitCurrentScreen(store, "flow-first")
        waitUntilExactlyOneExists(hasText("Flow First Body"), timeoutMillis = UI_TEST_WAIT_MS)
        waitUntil(timeoutMillis = UI_TEST_WAIT_MS) {
            onAllNodesWithText("Home Body").fetchSemanticsNodes().isEmpty()
        }

        // The exiting screen shares the flow layout with the arriving one, so during the transition
        // that layout encloses both. Once the transition settles only the arriving screen is left
        // under it, and the entry beneath on the back stack has no layout at all.
        store.launch { store.navigation { navigateTo("flow/flow-second", replaceCurrent = true) } }
        awaitCurrentScreen(store, "flow-second")
        waitUntilExactlyOneExists(hasText("Flow Second Body"), timeoutMillis = UI_TEST_WAIT_MS)
        waitUntil(timeoutMillis = UI_TEST_WAIT_MS) {
            onAllNodesWithText("Flow First Body").fetchSemanticsNodes().isEmpty()
        }
        waitForIdle()

        assertEquals(
            0,
            Recorders.second.disposals,
            "the screen is still on the back stack, so nothing it composed may be disposed"
        )
        assertEquals(
            1,
            Recorders.second.mounts,
            "a settling transition must not tear the screen down and compose it a second time"
        )
    }

    @Test
    fun a_screen_is_not_rebuilt_when_the_next_one_animates_in_over_it() = runComposeUiTest {
        val store = createStore { module(module()) }
        setContent { StoreProvider(store) { NavigationRender() } }
        waitUntilExactlyOneExists(hasText("Home Body"), timeoutMillis = UI_TEST_WAIT_MS)

        store.launch { store.navigation { navigateTo("flow/flow-first") } }
        awaitCurrentScreen(store, "flow-first")
        waitUntilExactlyOneExists(hasText("Flow First Body"), timeoutMillis = UI_TEST_WAIT_MS)
        waitForIdle()

        store.launch { store.navigation { navigateTo("flow/flow-second") } }
        awaitCurrentScreen(store, "flow-second")
        waitUntilExactlyOneExists(hasText("Flow Second Body"), timeoutMillis = UI_TEST_WAIT_MS)
        waitUntil(timeoutMillis = UI_TEST_WAIT_MS) {
            onAllNodesWithText("Flow First Body").fetchSemanticsNodes().isEmpty()
        }

        assertEquals(
            1,
            Recorders.first.mounts,
            "moving from the front slot to the exiting one must not rebuild the screen"
        )
    }

    @Test
    fun screens_sharing_a_graph_layout_are_laid_out_on_top_of_each_other() = runComposeUiTest {
        val store = createStore { module(module()) }
        setContent { StoreProvider(store) { NavigationRender() } }
        waitUntilExactlyOneExists(hasText("Home Body"), timeoutMillis = UI_TEST_WAIT_MS)

        store.launch { store.navigation { navigateTo("flow/flow-first") } }
        awaitCurrentScreen(store, "flow-first")
        waitUntilExactlyOneExists(hasText("Flow First Body"), timeoutMillis = UI_TEST_WAIT_MS)
        waitForIdle()

        store.launch { store.navigation { navigateTo("flow/flow-second") } }
        awaitCurrentScreen(store, "flow-second")
        waitUntilExactlyOneExists(hasText("Flow Second Body"), timeoutMillis = UI_TEST_WAIT_MS)
        waitForIdle()

        // Both screens were on screen together, under a layout that is a Column. Handing that
        // Column the screens as its own children would lay the arriving one out after the exiting
        // one rather than over it, so where the arriving screen was first measured says which of
        // the two happened.
        val exitingTop = assertNotNull(Recorders.first.firstTop)
        val enteringTop = assertNotNull(Recorders.second.firstTop)
        assertEquals(
            exitingTop,
            enteringTop,
            "both screens begin directly under the graph chrome, not one after the other"
        )
    }

    @Test
    fun a_screen_is_disposed_once_it_actually_leaves_the_back_stack() = runComposeUiTest {
        val store = createStore { module(module()) }
        setContent { StoreProvider(store) { NavigationRender() } }
        waitUntilExactlyOneExists(hasText("Home Body"), timeoutMillis = UI_TEST_WAIT_MS)

        store.launch { store.navigation { navigateTo("flow/flow-second") } }
        awaitCurrentScreen(store, "flow-second")
        waitUntilExactlyOneExists(hasText("Flow Second Body"), timeoutMillis = UI_TEST_WAIT_MS)

        store.launch { store.navigation { navigateBack() } }
        awaitCurrentScreen(store, "home")
        waitUntil(timeoutMillis = UI_TEST_WAIT_MS) {
            onAllNodesWithText("Flow Second Body").fetchSemanticsNodes().isEmpty()
        }
        waitForIdle()

        assertEquals(
            1,
            Recorders.second.disposals,
            "leaving the back stack is what disposes a screen"
        )
    }
}
