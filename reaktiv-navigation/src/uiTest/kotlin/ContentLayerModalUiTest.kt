import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertTopPositionInRootIsEqualTo
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.waitUntilExactlyOneExists
import androidx.compose.ui.unit.dp
import io.github.syrou.reaktiv.compose.StoreProvider
import io.github.syrou.reaktiv.core.Store
import io.github.syrou.reaktiv.core.createStore
import io.github.syrou.reaktiv.navigation.createNavigationModule
import io.github.syrou.reaktiv.navigation.definition.Modal
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.extension.navigateBack
import io.github.syrou.reaktiv.navigation.extension.navigation
import io.github.syrou.reaktiv.navigation.layer.RenderLayer
import io.github.syrou.reaktiv.navigation.param.Params
import io.github.syrou.reaktiv.navigation.transition.NavTransition
import io.github.syrou.reaktiv.navigation.ui.NavigationRender
import kotlinx.coroutines.launch
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class, ExperimentalMaterial3Api::class)
class ContentLayerModalUiTest {

    private object HomeBodyScreen : Screen {
        override val route = "home-body"
        override val enterTransition = NavTransition.None
        override val exitTransition = NavTransition.None

        @Composable
        override fun Content(params: Params) {
            Box(modifier = Modifier.fillMaxSize().testTag("home-body")) { Text("Home body") }
        }
    }

    private object ContentModal : Modal {
        override val route = "content-modal"
        override val enterTransition = NavTransition.None
        override val exitTransition = NavTransition.None
        override val renderLayer = RenderLayer.CONTENT

        @Composable
        override fun Content(params: Params) {
            Box(modifier = Modifier.fillMaxSize().testTag("content-modal")) { Text("Content modal") }
        }
    }

    private object OverlayModal : Modal {
        override val route = "overlay-modal"
        override val enterTransition = NavTransition.None
        override val exitTransition = NavTransition.None

        @Composable
        override fun Content(params: Params) {
            Box(modifier = Modifier.fillMaxSize().testTag("overlay-modal")) { Text("Overlay modal") }
        }
    }

    private object FadingOverlayModal : Modal {
        override val route = "fading-overlay-modal"
        override val enterTransition = NavTransition.Fade
        override val exitTransition = NavTransition.Fade

        @Composable
        override fun Content(params: Params) {
            Box(modifier = Modifier.fillMaxSize().testTag("fading-overlay-modal")) { Text("Fading modal") }
        }
    }

    @Composable
    private fun HomeScaffoldLike(content: @Composable () -> Unit) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = { TopAppBar(title = { Text("Home") }, modifier = Modifier.testTag("home-top-bar")) },
            bottomBar = { NavigationBar(modifier = Modifier.testTag("home-bottom-bar")) { } }
        ) { padding ->
            Box(modifier = Modifier.fillMaxSize().padding(padding).testTag("home-content-slot")) {
                content()
            }
        }
    }

    private fun buildStore(): Store = createStore {
        module(createNavigationModule {
            rootGraph {
                start("home")
                graph("home") {
                    start(HomeBodyScreen)
                    screens(HomeBodyScreen)
                    modals(ContentModal, OverlayModal, FadingOverlayModal)
                    layout { content -> HomeScaffoldLike(content) }
                }
            }
        })
    }

    private fun ComposeUiTest.mount(): Store {
        val store = buildStore()
        setContent {
            StoreProvider(store) {
                NavigationRender()
            }
        }
        waitUntilExactlyOneExists(hasText("Home body"), timeoutMillis = UI_TEST_WAIT_MS)
        return store
    }

    @Test
    fun contentModalDrawsInsideTheGraphLayoutOverTheUnderlyingScreen() = runComposeUiTest {
        val store = mount()

        store.launch { store.navigation { navigateTo(ContentModal) } }
        awaitCurrentScreen(store, "content-modal")
        waitUntilExactlyOneExists(hasText("Content modal"), timeoutMillis = UI_TEST_WAIT_MS)
        waitForIdle()

        onNodeWithText("Home body").assertExists()
        val slot = onNodeWithTag("home-content-slot").getUnclippedBoundsInRoot()
        onNodeWithTag("content-modal").assertTopPositionInRootIsEqualTo(slot.top)
        onNodeWithTag("content-modal").assertHeightIsEqualTo(slot.bottom - slot.top)
        onNodeWithTag("home-bottom-bar").assertExists()
    }

    @Test
    fun overlayModalCoversTheGraphLayout() = runComposeUiTest {
        val store = mount()

        store.launch { store.navigation { navigateTo(OverlayModal) } }
        awaitCurrentScreen(store, "overlay-modal")
        waitUntilExactlyOneExists(hasText("Overlay modal"), timeoutMillis = UI_TEST_WAIT_MS)
        waitForIdle()

        val root = onRoot().getUnclippedBoundsInRoot()
        onNodeWithTag("overlay-modal").assertTopPositionInRootIsEqualTo(0.dp)
        onNodeWithTag("overlay-modal").assertHeightIsEqualTo(root.bottom - root.top)
    }

    @Test
    fun dismissingContentModalLeavesTheUnderlyingScreenInPlace() = runComposeUiTest {
        val store = mount()

        store.launch { store.navigation { navigateTo(ContentModal) } }
        awaitCurrentScreen(store, "content-modal")
        waitUntilExactlyOneExists(hasText("Content modal"), timeoutMillis = UI_TEST_WAIT_MS)

        store.launch { store.navigateBack() }
        awaitCurrentScreen(store, "home-body")
        waitUntil(timeoutMillis = UI_TEST_WAIT_MS) { onAllNodesWithText("Content modal").fetchSemanticsNodes().isEmpty() }

        onNodeWithText("Home body").assertExists()
    }

    @Test
    fun dismissingOverlayModalWithNoTransitionRemovesIt() = runComposeUiTest {
        val store = mount()

        store.launch { store.navigation { navigateTo(OverlayModal) } }
        awaitCurrentScreen(store, "overlay-modal")
        waitUntilExactlyOneExists(hasText("Overlay modal"), timeoutMillis = UI_TEST_WAIT_MS)

        store.launch { store.navigateBack() }
        awaitCurrentScreen(store, "home-body")
        waitUntil(timeoutMillis = UI_TEST_WAIT_MS) { onAllNodesWithText("Overlay modal").fetchSemanticsNodes().isEmpty() }
    }

    @Test
    fun dismissingOverlayModalWithFadeRemovesIt() = runComposeUiTest {
        val store = mount()

        store.launch { store.navigation { navigateTo(FadingOverlayModal) } }
        awaitCurrentScreen(store, "fading-overlay-modal")
        waitUntilExactlyOneExists(hasText("Fading modal"), timeoutMillis = UI_TEST_WAIT_MS)

        store.launch { store.navigateBack() }
        awaitCurrentScreen(store, "home-body")
        waitUntil(timeoutMillis = UI_TEST_WAIT_MS) { onAllNodesWithText("Fading modal").fetchSemanticsNodes().isEmpty() }
    }
}
