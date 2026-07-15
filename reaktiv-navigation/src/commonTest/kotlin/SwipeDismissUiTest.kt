import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.waitUntilExactlyOneExists
import androidx.compose.ui.unit.dp
import io.github.syrou.reaktiv.compose.StoreProvider
import io.github.syrou.reaktiv.core.Middleware
import io.github.syrou.reaktiv.core.Store
import io.github.syrou.reaktiv.core.StoreAccessor
import io.github.syrou.reaktiv.core.createStore
import io.github.syrou.reaktiv.navigation.NavigationAction
import io.github.syrou.reaktiv.navigation.createNavigationModule
import io.github.syrou.reaktiv.navigation.definition.Modal
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.extension.navigateBack
import io.github.syrou.reaktiv.navigation.extension.navigation
import io.github.syrou.reaktiv.navigation.param.Params
import io.github.syrou.reaktiv.navigation.transition.NavTransition
import io.github.syrou.reaktiv.navigation.ui.NavigationRender
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class SwipeDismissUiTest {

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
    fun sheetDragDownPastThresholdDismissesExactlyOnce() = runComposeUiTest {
        val recorder = BackActionRecorder()
        val store = buildStore(recorder)
        setContent {
            StoreProvider(store) {
                NavigationRender()
            }
        }
        waitUntilExactlyOneExists(hasText("UI Home"), timeoutMillis = 5000)
        store.launch { store.navigation { navigateTo("ui-sheet") } }
        waitUntilExactlyOneExists(hasText("UI Sheet"), timeoutMillis = 5000)
        waitUntil(timeoutMillis = 5000) { onAllNodesWithText("UI Home").fetchSemanticsNodes().isEmpty() }

        onRoot().performTouchInput {
            down(Offset(centerX, height * 0.2f))
            repeat(8) {
                moveBy(Offset(0f, height * 0.07f), delayMillis = 30)
            }
            moveBy(Offset(0f, 2f), delayMillis = 100)
            up()
        }
        waitUntilExactlyOneExists(hasText("UI Home"), timeoutMillis = 5000)
        waitUntil(timeoutMillis = 5000) { onAllNodesWithText("UI Sheet").fetchSemanticsNodes().isEmpty() }
        assertEquals(1, recorder.backActions.size)
    }

    @Test
    fun sheetPartialDragCancelKeepsSheetAndShowsUnderlyingDuringDrag() = runComposeUiTest {
        val recorder = BackActionRecorder()
        val store = buildStore(recorder)
        setContent {
            StoreProvider(store) {
                NavigationRender()
            }
        }
        waitUntilExactlyOneExists(hasText("UI Home"), timeoutMillis = 5000)
        store.launch { store.navigation { navigateTo("ui-sheet") } }
        waitUntilExactlyOneExists(hasText("UI Sheet"), timeoutMillis = 5000)
        waitUntil(timeoutMillis = 5000) { onAllNodesWithText("UI Home").fetchSemanticsNodes().isEmpty() }

        onRoot().performTouchInput {
            down(Offset(centerX, height * 0.2f))
            moveBy(Offset(0f, 60f))
            moveBy(Offset(0f, 60f))
        }
        waitUntilExactlyOneExists(hasText("UI Home"), timeoutMillis = 5000)
        onNodeWithText("UI Sheet").assertExists()
        assertEquals(0, recorder.backActions.size)

        onRoot().performTouchInput {
            moveBy(Offset(0f, 2f), delayMillis = 100)
            moveBy(Offset(0f, 2f), delayMillis = 100)
            up()
        }
        waitUntil(timeoutMillis = 5000) { onAllNodesWithText("UI Home").fetchSemanticsNodes().isEmpty() }
        onNodeWithText("UI Sheet").assertExists()
        assertEquals(0, recorder.backActions.size)
    }

    @Test
    fun verticalDragOnNonDismissScreenIsInert() = runComposeUiTest {
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
            down(Offset(centerX, height * 0.2f))
            repeat(6) {
                moveBy(Offset(0f, height * 0.1f), delayMillis = 30)
            }
            up()
        }
        waitForIdle()
        onNodeWithText("UI Detail").assertExists()
        assertEquals(0, recorder.backActions.size)
    }

    @Test
    fun modalSwipeDownDismissesExactlyOnce() = runComposeUiTest {
        val recorder = BackActionRecorder()
        val swipeModal = object : Modal {
            override val route = "swipe-modal"
            override val enterTransition = NavTransition.SlideUpBottom
            override val exitTransition = NavTransition.SlideOutBottom
            override val swipeToDismiss = true

            @Composable
            override fun Content(params: Params) {
                Box(modifier = Modifier.size(200.dp).testTag("swipe-modal-content")) {
                    Text("Swipe Modal")
                }
            }
        }
        val module = createNavigationModule {
            rootGraph {
                startScreen(UiHomeScreen)
                screens(UiHomeScreen)
                modals(swipeModal)
            }
        }
        val store = createStore {
            module(module)
            middlewares(recorder.middleware)
        }
        setContent {
            StoreProvider(store) {
                NavigationRender()
            }
        }
        waitUntilExactlyOneExists(hasText("UI Home"), timeoutMillis = 5000)
        store.launch { store.navigation { navigateTo("swipe-modal") } }
        waitUntilExactlyOneExists(hasText("Swipe Modal"), timeoutMillis = 5000)

        onRoot().performTouchInput {
            down(Offset(centerX, centerY))
            repeat(8) {
                moveBy(Offset(0f, height * 0.07f), delayMillis = 30)
            }
            moveBy(Offset(0f, 2f), delayMillis = 100)
            up()
        }
        waitUntil(timeoutMillis = 5000) { onAllNodesWithText("Swipe Modal").fetchSemanticsNodes().isEmpty() }
        onNodeWithText("UI Home").assertExists()
        assertEquals(1, recorder.backActions.size)
    }

    @Test
    fun modalIsSwipeDismissableByDefault() = runComposeUiTest {
        val recorder = BackActionRecorder()
        val defaultModal = object : Modal {
            override val route = "default-modal"
            override val enterTransition = NavTransition.SlideUpBottom
            override val exitTransition = NavTransition.SlideOutBottom

            @Composable
            override fun Content(params: Params) {
                Box(modifier = Modifier.size(200.dp).testTag("default-modal-content")) {
                    Text("Default Modal")
                }
            }
        }
        val module = createNavigationModule {
            rootGraph {
                startScreen(UiHomeScreen)
                screens(UiHomeScreen)
                modals(defaultModal)
            }
        }
        val store = createStore {
            module(module)
            middlewares(recorder.middleware)
        }
        setContent {
            StoreProvider(store) {
                NavigationRender()
            }
        }
        waitUntilExactlyOneExists(hasText("UI Home"), timeoutMillis = 5000)
        store.launch { store.navigation { navigateTo("default-modal") } }
        waitUntilExactlyOneExists(hasText("Default Modal"), timeoutMillis = 5000)

        onRoot().performTouchInput {
            down(Offset(centerX, centerY))
            repeat(8) {
                moveBy(Offset(0f, height * 0.07f), delayMillis = 30)
            }
            moveBy(Offset(0f, 2f), delayMillis = 100)
            up()
        }
        waitUntil(timeoutMillis = 5000) { onAllNodesWithText("Default Modal").fetchSemanticsNodes().isEmpty() }
        onNodeWithText("UI Home").assertExists()
        assertEquals(1, recorder.backActions.size)
    }

    @Test
    fun onDismissRequestOverridesBackAndCanDecline() = runComposeUiTest {
        val recorder = BackActionRecorder()
        val dismissRequests = mutableListOf<Unit>()
        val decliningHandler: suspend StoreAccessor.() -> Unit = {
            dismissRequests.add(Unit)
        }
        val guardedSheet = object : Screen {
            override val route = "guarded-sheet"
            override val enterTransition = NavTransition.SlideUpBottom
            override val exitTransition = NavTransition.SlideOutBottom
            override val swipeToDismiss = true
            override val onDismissRequest: (suspend StoreAccessor.() -> Unit) = decliningHandler

            @Composable
            override fun Content(params: Params) {
                Box(modifier = Modifier.fillMaxSize().testTag("guarded-sheet-screen")) {
                    Text("Guarded Sheet")
                }
            }
        }
        val module = createNavigationModule {
            rootGraph {
                startScreen(UiHomeScreen)
                screens(UiHomeScreen, guardedSheet)
            }
        }
        val store = createStore {
            module(module)
            middlewares(recorder.middleware)
        }
        setContent {
            StoreProvider(store) {
                NavigationRender()
            }
        }
        waitUntilExactlyOneExists(hasText("UI Home"), timeoutMillis = 5000)
        store.launch { store.navigation { navigateTo("guarded-sheet") } }
        waitUntilExactlyOneExists(hasText("Guarded Sheet"), timeoutMillis = 5000)
        waitUntil(timeoutMillis = 5000) { onAllNodesWithText("UI Home").fetchSemanticsNodes().isEmpty() }

        onRoot().performTouchInput {
            down(Offset(centerX, height * 0.2f))
            repeat(8) {
                moveBy(Offset(0f, height * 0.07f), delayMillis = 30)
            }
            moveBy(Offset(0f, 2f), delayMillis = 100)
            up()
        }
        waitUntil(timeoutMillis = 5000) { dismissRequests.size == 1 }
        waitUntil(timeoutMillis = 5000) { onAllNodesWithText("UI Home").fetchSemanticsNodes().isEmpty() }
        onNodeWithText("Guarded Sheet").assertExists()
        assertEquals(0, recorder.backActions.size)
        assertEquals(1, dismissRequests.size)
    }

    @Test
    fun deprecatedTapOutsideClickStillDismisses() = runComposeUiTest {
        val recorder = BackActionRecorder()
        val tapModal = object : Modal {
            override val route = "tap-modal"
            override val enterTransition = NavTransition.None
            override val exitTransition = NavTransition.None

            @Deprecated("test uses legacy api")
            @Suppress("OVERRIDE_DEPRECATION")
            override val tapOutsideClick: (suspend StoreAccessor.() -> Unit) = { navigateBack() }

            @Composable
            override fun Content(params: Params) {
                Box(
                    modifier = Modifier.size(100.dp).testTag("tap-modal-content"),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Tap Modal")
                }
            }
        }
        val module = createNavigationModule {
            rootGraph {
                startScreen(UiHomeScreen)
                screens(UiHomeScreen)
                modals(tapModal)
            }
        }
        val store = createStore {
            module(module)
            middlewares(recorder.middleware)
        }
        setContent {
            StoreProvider(store) {
                NavigationRender()
            }
        }
        waitUntilExactlyOneExists(hasText("UI Home"), timeoutMillis = 5000)
        store.launch { store.navigation { navigateTo("tap-modal") } }
        waitUntilExactlyOneExists(hasText("Tap Modal"), timeoutMillis = 5000)

        onRoot().performTouchInput {
            down(Offset(20f, 20f))
            up()
        }
        waitUntil(timeoutMillis = 5000) { onAllNodesWithText("Tap Modal").fetchSemanticsNodes().isEmpty() }
        assertEquals(1, recorder.backActions.size)
    }

    @Test
    fun modalBackgroundStillBlocksClickThrough() = runComposeUiTest {
        val recorder = BackActionRecorder()
        var homeClicks = 0
        val clickHome = object : Screen {
            override val route = "click-home"
            override val enterTransition = NavTransition.None
            override val exitTransition = NavTransition.None

            @Composable
            override fun Content(params: Params) {
                Box(modifier = Modifier.fillMaxSize().clickable { homeClicks++ }) {
                    Text("Click Home")
                }
            }
        }
        val blockingModal = object : Modal {
            override val route = "blocking-modal"
            override val enterTransition = NavTransition.None
            override val exitTransition = NavTransition.None
            override val swipeToDismiss = true

            @Composable
            override fun Content(params: Params) {
                Box(modifier = Modifier.size(100.dp), contentAlignment = Alignment.Center) {
                    Text("Blocking Modal")
                }
            }
        }
        val module = createNavigationModule {
            rootGraph {
                startScreen(clickHome)
                screens(clickHome)
                modals(blockingModal)
            }
        }
        val store = createStore {
            module(module)
            middlewares(recorder.middleware)
        }
        setContent {
            StoreProvider(store) {
                NavigationRender()
            }
        }
        waitUntilExactlyOneExists(hasText("Click Home"), timeoutMillis = 5000)
        store.launch { store.navigation { navigateTo("blocking-modal") } }
        waitUntilExactlyOneExists(hasText("Blocking Modal"), timeoutMillis = 5000)

        onRoot().performTouchInput {
            down(Offset(20f, 20f))
            up()
        }
        waitForIdle()
        assertEquals(0, homeClicks)
        onNodeWithText("Blocking Modal").assertExists()
        assertEquals(0, recorder.backActions.size)
    }
}
