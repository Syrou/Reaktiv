import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.waitUntilExactlyOneExists
import androidx.compose.ui.unit.dp
import io.github.syrou.reaktiv.compose.StoreProvider
import io.github.syrou.reaktiv.core.Middleware
import io.github.syrou.reaktiv.core.createStore
import io.github.syrou.reaktiv.navigation.NavigationAction
import io.github.syrou.reaktiv.navigation.createNavigationModule
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.extension.navigation
import io.github.syrou.reaktiv.navigation.param.Params
import io.github.syrou.reaktiv.navigation.transition.NavTransition
import io.github.syrou.reaktiv.navigation.ui.NavigationRender
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class PullToRefreshDismissUiTest {

    private object RefreshRecorder {
        var count = 0
        val refreshing = androidx.compose.runtime.mutableStateOf(false)
    }

    private object UiRefreshableSheetScreen : Screen {
        override val route = "ui-refresh-sheet"
        override val enterTransition = NavTransition.SlideUpBottom
        override val exitTransition = NavTransition.SlideOutBottom

        @OptIn(ExperimentalMaterial3Api::class)
        @Composable
        override fun Content(params: Params) {
            PullToRefreshBox(
                isRefreshing = RefreshRecorder.refreshing.value,
                onRefresh = { RefreshRecorder.count++ },
                modifier = Modifier.fillMaxSize().testTag("refresh-box")
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("refresh-list")
                        .verticalScroll(rememberScrollState())
                ) {
                    repeat(40) { index ->
                        Text(
                            text = "Refresh Row $index",
                            modifier = Modifier.height(48.dp)
                        )
                    }
                }
            }
        }
    }

    private object UiHeaderedRefreshSheetScreen : Screen {
        override val route = "ui-headered-refresh-sheet"
        override val enterTransition = NavTransition.SlideUpBottom
        override val exitTransition = NavTransition.SlideOutBottom

        @OptIn(ExperimentalMaterial3Api::class)
        @Composable
        override fun Content(params: Params) {
            Column(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(72.dp)
                        .testTag("sheet-header")
                ) {
                    Text("Sheet Header")
                }
                PullToRefreshBox(
                    isRefreshing = RefreshRecorder.refreshing.value,
                    onRefresh = { RefreshRecorder.count++ },
                    modifier = Modifier.fillMaxSize()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("headered-refresh-list")
                            .verticalScroll(rememberScrollState())
                    ) {
                        repeat(40) { index ->
                            Text(
                                text = "Headered Row $index",
                                modifier = Modifier.height(48.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    private object UiSurfacedLazySheetScreen : Screen {
        override val route = "ui-surfaced-lazy-sheet"
        override val enterTransition = NavTransition.SlideUpBottom
        override val exitTransition = NavTransition.SlideOutBottom

        @OptIn(ExperimentalMaterial3Api::class)
        @Composable
        override fun Content(params: Params) {
            Surface(modifier = Modifier.fillMaxSize()) {
                PullToRefreshBox(
                    isRefreshing = RefreshRecorder.refreshing.value,
                    onRefresh = { RefreshRecorder.count++ },
                    modifier = Modifier.fillMaxSize()
                ) {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("surfaced-lazy-list")
                    ) {
                        items(40) { index ->
                            Text(
                                text = "Lazy Row $index",
                                modifier = Modifier.height(48.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    private object UiChromeHostScreen : Screen {
        override val route = "ui-chrome-host"
        override val enterTransition = NavTransition.None
        override val exitTransition = NavTransition.None

        @Composable
        override fun Content(params: Params) {
            Text("Chrome Host")
        }
    }

    private object UiChromeInnerSheetScreen : Screen {
        override val route = "ui-chrome-inner-sheet"
        override val enterTransition = NavTransition.SlideUpBottom
        override val exitTransition = NavTransition.SlideOutBottom

        @Composable
        override fun Content(params: Params) {
            Column(modifier = Modifier.fillMaxSize()) {
                repeat(10) { index ->
                    Text(
                        text = "Inner Row $index",
                        modifier = Modifier.height(48.dp)
                    )
                }
            }
        }
    }

    private object UiFullDemoSheetScreen : Screen {
        override val route = "ui-full-demo-sheet"
        override val enterTransition = NavTransition.SlideUpBottom
        override val exitTransition = NavTransition.SlideOutBottom

        @OptIn(ExperimentalMaterial3Api::class)
        @Composable
        override fun Content(params: Params) {
            val scope = rememberCoroutineScope()
            var isRefreshing by remember { mutableStateOf(false) }
            var refreshCount by remember { mutableIntStateOf(0) }
            var listItems by remember { mutableStateOf((1..30).map { "Demo Row $it" }) }
            Surface(modifier = Modifier.fillMaxSize()) {
                PullToRefreshBox(
                    isRefreshing = isRefreshing,
                    onRefresh = {
                        scope.launch {
                            isRefreshing = true
                            delay(3000)
                            refreshCount++
                            listItems = listOf("Refreshed #$refreshCount") + listItems
                            isRefreshing = false
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                ) {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(listItems.size) { index ->
                            Text(
                                text = listItems[index],
                                modifier = Modifier.height(48.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    private class BackActionRecorder {
        val backActions = mutableListOf<NavigationAction.Back>()

        val middleware: Middleware = { action, _, _, updatedState ->
            if (action is NavigationAction.Back) {
                backActions.add(action)
            }
            updatedState(action)
        }
    }

    private fun createModule() = createNavigationModule {
        rootGraph {
            start(UiHomeScreen)
            screens(UiHomeScreen, UiRefreshableSheetScreen, UiHeaderedRefreshSheetScreen)
        }
    }

    @Test
    fun dragOnHeaderChromeDismissesWhileContentPullsToRefresh() = runComposeUiTest {
        RefreshRecorder.count = 0
        RefreshRecorder.refreshing.value = false
        val recorder = BackActionRecorder()
        val store = createStore {
            module(createModule())
            middlewares(recorder.middleware)
        }
        setContent {
            StoreProvider(store) {
                NavigationRender()
            }
        }
        waitUntilExactlyOneExists(hasText("UI Home"), timeoutMillis = UI_TEST_WAIT_MS)
        store.launch { store.navigation { navigateTo("ui-headered-refresh-sheet") } }
        awaitCurrentScreen(store, "ui-headered-refresh-sheet")
        waitUntilExactlyOneExists(hasText("Headered Row 0"), timeoutMillis = UI_TEST_WAIT_MS)
        waitUntil(timeoutMillis = UI_TEST_WAIT_MS) { onAllNodesWithText("UI Home").fetchSemanticsNodes().isEmpty() }

        onRoot().performTouchInput {
            down(Offset(centerX, 36f))
            repeat(8) {
                moveBy(Offset(0f, height * 0.07f), delayMillis = 30)
            }
            moveBy(Offset(0f, 2f), delayMillis = 100)
            up()
        }
        awaitCurrentScreen(store, "ui-home")
        waitUntilExactlyOneExists(hasText("UI Home"), timeoutMillis = UI_TEST_WAIT_MS)
        waitUntil(timeoutMillis = UI_TEST_WAIT_MS) { onAllNodesWithText("Headered Row 0").fetchSemanticsNodes().isEmpty() }

        assertEquals(1, recorder.backActions.size, "Dragging the header chrome should dismiss the sheet")
        assertEquals(0, RefreshRecorder.count, "A chrome drag must not trigger pull-to-refresh")
    }

    @Test
    fun dismissIndicatorRendersBelowGraphLayoutChromeAndShiftsContent() = runComposeUiTest {
        val store = createStore {
            module(createNavigationModule {
                rootGraph {
                    start("chrome-home")
                    graph("chrome-home") {
                        start(UiChromeHostScreen)
                        screens(UiChromeInnerSheetScreen)
                        layout { content ->
                            Column(modifier = Modifier.fillMaxSize()) {
                                Text(
                                    text = "Sheet Chrome",
                                    modifier = Modifier.height(56.dp)
                                )
                                content()
                            }
                        }
                    }
                }
            })
        }
        setContent {
            StoreProvider(store) {
                NavigationRender()
            }
        }
        waitUntilExactlyOneExists(hasText("Chrome Host"), timeoutMillis = UI_TEST_WAIT_MS)
        store.launch { store.navigation { navigateTo("chrome-home/ui-chrome-inner-sheet") } }
        awaitCurrentScreen(store, "chrome-home/ui-chrome-inner-sheet")
        waitUntilExactlyOneExists(hasText("Inner Row 0"), timeoutMillis = UI_TEST_WAIT_MS)
        waitForIdle()

        val pillBounds = onNodeWithTag("reaktiv-dismiss-indicator").fetchSemanticsNode().boundsInRoot
        val chromeBounds = onAllNodesWithText("Sheet Chrome").fetchSemanticsNodes().first().boundsInRoot
        val firstRowBounds = onAllNodesWithText("Inner Row 0").fetchSemanticsNodes().first().boundsInRoot

        assertTrue(
            pillBounds.top >= chromeBounds.bottom,
            "Indicator must render below the graph layout chrome (pill top ${pillBounds.top}, chrome bottom ${chromeBounds.bottom})"
        )
        assertTrue(
            firstRowBounds.top >= pillBounds.bottom,
            "Screen content must be shifted below the indicator slot (row top ${firstRowBounds.top}, pill bottom ${pillBounds.bottom})"
        )
    }

    @Test
    fun fullDemoScreenRefreshCycleWorksTwice() = runComposeUiTest {
        val store = createStore {
            module(createNavigationModule {
                rootGraph {
                    start("chrome-home")
                    screens(UiFullDemoSheetScreen)
                    graph("chrome-home") {
                        start(UiChromeHostScreen)
                        layout { content ->
                            Column(modifier = Modifier.fillMaxSize()) {
                                Text("Chrome Bar")
                                content()
                            }
                        }
                    }
                }
            })
        }
        setContent {
            StoreProvider(store) {
                NavigationRender()
            }
        }
        waitUntilExactlyOneExists(hasText("Chrome Host"), timeoutMillis = UI_TEST_WAIT_MS)
        store.launch { store.navigation { navigateTo("ui-full-demo-sheet") } }
        awaitCurrentScreen(store, "ui-full-demo-sheet")
        waitUntilExactlyOneExists(hasText("Demo Row 1"), timeoutMillis = UI_TEST_WAIT_MS)
        waitForIdle()

        onRoot().performTouchInput {
            down(Offset(centerX, height * 0.4f))
            repeat(8) {
                moveBy(Offset(0f, height * 0.07f), delayMillis = 30)
            }
            moveBy(Offset(0f, 2f), delayMillis = 100)
            up()
        }
        waitUntilExactlyOneExists(hasText("Refreshed #1"), timeoutMillis = 10000)
        waitForIdle()

        onRoot().performTouchInput {
            down(Offset(centerX, height * 0.4f))
            repeat(8) {
                moveBy(Offset(0f, height * 0.07f), delayMillis = 30)
            }
            moveBy(Offset(0f, 2f), delayMillis = 100)
            up()
        }
        waitUntilExactlyOneExists(hasText("Refreshed #2"), timeoutMillis = 10000)

        onAllNodesWithText("Chrome Host").fetchSemanticsNodes().isEmpty().let { hostHidden ->
            assertTrue(hostHidden, "The sheet must still be presented after two refresh cycles")
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Test
    fun contentInteractiveWithFullAppShellAroundNavigationRender() = runComposeUiTest {
        RefreshRecorder.count = 0
        RefreshRecorder.refreshing.value = false
        val store = createStore {
            module(createNavigationModule {
                rootGraph {
                    start("scaffold-home")
                    screens(UiSurfacedLazySheetScreen)
                    graph("scaffold-home") {
                        start(UiChromeHostScreen)
                        layout { content ->
                            Scaffold(
                                topBar = {
                                    TopAppBar(title = { Text("Scaffold Title") })
                                }
                            ) { padding ->
                                Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                                    content()
                                }
                            }
                        }
                    }
                }
            })
        }
        setContent {
            StoreProvider(store) {
                val navigationState by io.github.syrou.reaktiv.compose.composeState<io.github.syrou.reaktiv.navigation.NavigationState>()
                val drawerState = DrawerState(DrawerValue.Closed) { true }
                Surface(modifier = Modifier.fillMaxSize()) {
                    ModalNavigationDrawer(
                        drawerState = drawerState,
                        gesturesEnabled = drawerState.isOpen || !navigationState.canGoBack,
                        drawerContent = {
                            ModalDrawerSheet {
                                Text("Drawer Item")
                            }
                        }
                    ) {
                        NavigationRender(
                            modifier = Modifier
                                .fillMaxSize()
                                .systemBarsPadding()
                        )
                    }
                }
            }
        }
        waitUntilExactlyOneExists(hasText("Chrome Host"), timeoutMillis = UI_TEST_WAIT_MS)
        store.launch { store.navigation { navigateTo("ui-surfaced-lazy-sheet") } }
        awaitCurrentScreen(store, "ui-surfaced-lazy-sheet")
        waitUntilExactlyOneExists(hasText("Lazy Row 0"), timeoutMillis = UI_TEST_WAIT_MS)
        waitForIdle()

        scrollListUntilTextVisible("Lazy Row 20", revealLater = true)
        scrollListUntilTextVisible("Lazy Row 0", revealLater = false)

        onRoot().performTouchInput {
            down(Offset(centerX, height * 0.6f))
            repeat(10) {
                moveBy(Offset(0f, height * 0.08f), delayMillis = 30)
            }
            moveBy(Offset(0f, 2f), delayMillis = 100)
            up()
        }
        waitForIdle()
        onRoot().performTouchInput {
            down(Offset(centerX, height * 0.4f))
            repeat(8) {
                moveBy(Offset(0f, height * 0.07f), delayMillis = 30)
            }
            moveBy(Offset(0f, 2f), delayMillis = 100)
            up()
        }
        waitForIdle()

        assertTrue(RefreshRecorder.count >= 1, "Pull-to-refresh must work with the full app shell (Surface + drawer + systemBarsPadding) around NavigationRender")
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Test
    fun contentInteractiveOnSheetOverScaffoldGraphLayout() = runComposeUiTest {
        RefreshRecorder.count = 0
        RefreshRecorder.refreshing.value = false
        val store = createStore {
            module(createNavigationModule {
                rootGraph {
                    start("scaffold-home")
                    screens(UiSurfacedLazySheetScreen)
                    graph("scaffold-home") {
                        start(UiChromeHostScreen)
                        layout { content ->
                            Scaffold(
                                topBar = {
                                    TopAppBar(title = { Text("Scaffold Title") })
                                },
                                bottomBar = {
                                    NavigationBar {
                                        NavigationBarItem(
                                            selected = true,
                                            onClick = { },
                                            icon = { Text("A") },
                                            label = { Text("TabA") }
                                        )
                                    }
                                }
                            ) { padding ->
                                Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                                    content()
                                }
                            }
                        }
                    }
                }
            })
        }
        setContent {
            StoreProvider(store) {
                NavigationRender()
            }
        }
        waitUntilExactlyOneExists(hasText("Chrome Host"), timeoutMillis = UI_TEST_WAIT_MS)
        store.launch { store.navigation { navigateTo("ui-surfaced-lazy-sheet") } }
        awaitCurrentScreen(store, "ui-surfaced-lazy-sheet")
        waitUntilExactlyOneExists(hasText("Lazy Row 0"), timeoutMillis = UI_TEST_WAIT_MS)
        waitForIdle()

        scrollListUntilTextVisible("Lazy Row 20", revealLater = true)
        scrollListUntilTextVisible("Lazy Row 0", revealLater = false)

        onRoot().performTouchInput {
            down(Offset(centerX, height * 0.6f))
            repeat(10) {
                moveBy(Offset(0f, height * 0.08f), delayMillis = 30)
            }
            moveBy(Offset(0f, 2f), delayMillis = 100)
            up()
        }
        waitForIdle()
        onRoot().performTouchInput {
            down(Offset(centerX, height * 0.4f))
            repeat(8) {
                moveBy(Offset(0f, height * 0.07f), delayMillis = 30)
            }
            moveBy(Offset(0f, 2f), delayMillis = 100)
            up()
        }
        waitForIdle()

        assertTrue(RefreshRecorder.count >= 1, "Pull-to-refresh must work on a sheet presented over a Scaffold graph layout")
    }

    @Test
    fun contentStaysInteractiveAfterDismissAndReopenCycle() = runComposeUiTest {
        RefreshRecorder.count = 0
        RefreshRecorder.refreshing.value = false
        val store = createStore {
            module(createNavigationModule {
                rootGraph {
                    start("chrome-home")
                    screens(UiSurfacedLazySheetScreen)
                    graph("chrome-home") {
                        start(UiChromeHostScreen)
                        layout { content ->
                            Column(modifier = Modifier.fillMaxSize()) {
                                Text("Chrome Bar")
                                content()
                            }
                        }
                    }
                }
            })
        }
        setContent {
            StoreProvider(store) {
                NavigationRender()
            }
        }
        awaitCurrentScreen(store, "ui-chrome-host")
        store.launch { store.navigation { navigateTo("ui-surfaced-lazy-sheet") } }
        awaitCurrentScreen(store, "ui-surfaced-lazy-sheet")
        waitUntilExactlyOneExists(hasText("Lazy Row 0"), timeoutMillis = UI_TEST_WAIT_MS)
        waitForIdle()

        onRoot().performTouchInput {
            down(Offset(centerX, 14f))
            repeat(8) {
                moveBy(Offset(0f, height * 0.07f), delayMillis = 30)
            }
            moveBy(Offset(0f, 2f), delayMillis = 100)
            up()
        }
        awaitCurrentScreen(store, "ui-chrome-host")
        waitUntilExactlyOneExists(hasText("Chrome Host"), timeoutMillis = UI_TEST_WAIT_MS)

        store.launch { store.navigation { navigateTo("ui-surfaced-lazy-sheet") } }
        awaitCurrentScreen(store, "ui-surfaced-lazy-sheet")
        waitUntilExactlyOneExists(hasText("Lazy Row 0"), timeoutMillis = UI_TEST_WAIT_MS)
        waitForIdle()

        scrollListUntilTextVisible("Lazy Row 20", revealLater = true)
        scrollListUntilTextVisible("Lazy Row 0", revealLater = false)

        onRoot().performTouchInput {
            down(Offset(centerX, height * 0.6f))
            repeat(10) {
                moveBy(Offset(0f, height * 0.08f), delayMillis = 30)
            }
            moveBy(Offset(0f, 2f), delayMillis = 100)
            up()
        }
        waitForIdle()
        onRoot().performTouchInput {
            down(Offset(centerX, height * 0.4f))
            repeat(8) {
                moveBy(Offset(0f, height * 0.07f), delayMillis = 30)
            }
            moveBy(Offset(0f, 2f), delayMillis = 100)
            up()
        }
        waitForIdle()

        assertTrue(RefreshRecorder.count >= 1, "Pull-to-refresh must still work after a dismiss-and-reopen cycle")
    }

    @Test
    fun pullToRefreshWorksOnPremountedCrossHierarchySheet() = runComposeUiTest {
        RefreshRecorder.count = 0
        RefreshRecorder.refreshing.value = false
        val recorder = BackActionRecorder()
        val store = createStore {
            module(createNavigationModule {
                rootGraph {
                    start("chrome-home")
                    screens(UiSurfacedLazySheetScreen)
                    graph("chrome-home") {
                        start(UiChromeHostScreen)
                        layout { content ->
                            Column(modifier = Modifier.fillMaxSize()) {
                                Text("Chrome Bar")
                                content()
                            }
                        }
                    }
                }
            })
            middlewares(recorder.middleware)
        }
        setContent {
            StoreProvider(store) {
                NavigationRender()
            }
        }
        waitUntilExactlyOneExists(hasText("Chrome Host"), timeoutMillis = UI_TEST_WAIT_MS)
        store.launch { store.navigation { navigateTo("ui-surfaced-lazy-sheet") } }
        awaitCurrentScreen(store, "ui-surfaced-lazy-sheet")
        waitUntilExactlyOneExists(hasText("Lazy Row 0"), timeoutMillis = UI_TEST_WAIT_MS)
        waitUntil(timeoutMillis = UI_TEST_WAIT_MS) { onAllNodesWithText("Chrome Host").fetchSemanticsNodes().isEmpty() }
        waitForIdle()

        onRoot().performTouchInput {
            down(Offset(centerX, height * 0.4f))
            repeat(8) {
                moveBy(Offset(0f, height * 0.07f), delayMillis = 30)
            }
            moveBy(Offset(0f, 2f), delayMillis = 100)
            up()
        }
        waitForIdle()

        onNodeWithTag("surfaced-lazy-list").assertExists()
        assertEquals(0, recorder.backActions.size, "A mid-screen pull must not dismiss the premounted sheet")
        assertTrue(RefreshRecorder.count >= 1, "Pull-to-refresh should trigger on a premounted cross-hierarchy sheet")

        scrollListUntilTextVisible("Lazy Row 20", revealLater = true)
    }

    @Test
    fun dragFromTopEdgeDismissesOverFullScreenPullToRefreshContent() = runComposeUiTest {
        RefreshRecorder.count = 0
        RefreshRecorder.refreshing.value = false
        val recorder = BackActionRecorder()
        val store = createStore {
            module(createModule())
            middlewares(recorder.middleware)
        }
        setContent {
            StoreProvider(store) {
                NavigationRender()
            }
        }
        waitUntilExactlyOneExists(hasText("UI Home"), timeoutMillis = UI_TEST_WAIT_MS)
        store.launch { store.navigation { navigateTo("ui-refresh-sheet") } }
        awaitCurrentScreen(store, "ui-refresh-sheet")
        waitUntilExactlyOneExists(hasText("Refresh Row 0"), timeoutMillis = UI_TEST_WAIT_MS)
        waitUntil(timeoutMillis = UI_TEST_WAIT_MS) { onAllNodesWithText("UI Home").fetchSemanticsNodes().isEmpty() }

        onRoot().performTouchInput {
            down(Offset(centerX, 16f))
            repeat(8) {
                moveBy(Offset(0f, height * 0.07f), delayMillis = 30)
            }
            moveBy(Offset(0f, 2f), delayMillis = 100)
            up()
        }
        awaitCurrentScreen(store, "ui-home")
        waitUntilExactlyOneExists(hasText("UI Home"), timeoutMillis = UI_TEST_WAIT_MS)
        waitUntil(timeoutMillis = UI_TEST_WAIT_MS) { onAllNodesWithText("Refresh Row 0").fetchSemanticsNodes().isEmpty() }

        assertEquals(1, recorder.backActions.size, "A drag starting in the top edge zone should dismiss even over pull-to-refresh content")
        assertEquals(0, RefreshRecorder.count, "The top edge drag must not trigger pull-to-refresh")
    }

    @Test
    fun dragDuringActiveRefreshDismissesLikeAnyAtTopSheet() = runComposeUiTest {
        RefreshRecorder.count = 0
        RefreshRecorder.refreshing.value = true
        val recorder = BackActionRecorder()
        val store = createStore {
            module(createModule())
            middlewares(recorder.middleware)
        }
        setContent {
            StoreProvider(store) {
                NavigationRender()
            }
        }
        waitUntilExactlyOneExists(hasText("UI Home"), timeoutMillis = UI_TEST_WAIT_MS)
        store.launch { store.navigation { navigateTo("ui-refresh-sheet") } }
        awaitCurrentScreen(store, "ui-refresh-sheet")
        waitUntilExactlyOneExists(hasText("Refresh Row 0"), timeoutMillis = UI_TEST_WAIT_MS)
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
        waitUntil(timeoutMillis = UI_TEST_WAIT_MS) { onAllNodesWithText("Refresh Row 0").fetchSemanticsNodes().isEmpty() }

        assertEquals(1, recorder.backActions.size, "An actively refreshing sheet stays dismissible, matching platform sheet behavior")
        assertEquals(0, RefreshRecorder.count, "The drag must not re-trigger onRefresh while already refreshing")
    }

    @Test
    fun pullToRefreshWinsOverSwipeDismissOnScrollableContentAtTop() = runComposeUiTest {
        RefreshRecorder.count = 0
        RefreshRecorder.refreshing.value = false
        val recorder = BackActionRecorder()
        val store = createStore {
            module(createModule())
            middlewares(recorder.middleware)
        }
        setContent {
            StoreProvider(store) {
                NavigationRender()
            }
        }
        waitUntilExactlyOneExists(hasText("UI Home"), timeoutMillis = UI_TEST_WAIT_MS)
        store.launch { store.navigation { navigateTo("ui-refresh-sheet") } }
        awaitCurrentScreen(store, "ui-refresh-sheet")
        waitUntilExactlyOneExists(hasText("Refresh Row 0"), timeoutMillis = UI_TEST_WAIT_MS)
        waitUntil(timeoutMillis = UI_TEST_WAIT_MS) { onAllNodesWithText("UI Home").fetchSemanticsNodes().isEmpty() }

        onRoot().performTouchInput {
            down(Offset(centerX, height * 0.3f))
            repeat(8) {
                moveBy(Offset(0f, height * 0.07f), delayMillis = 30)
            }
            moveBy(Offset(0f, 2f), delayMillis = 100)
            up()
        }
        waitForIdle()

        onNodeWithTag("refresh-list").assertExists()
        assertEquals(0, recorder.backActions.size, "Swipe dismiss must not fire while pull-to-refresh owns the drag")
        assertTrue(RefreshRecorder.count >= 1, "Pull-to-refresh should have triggered")
    }
}
