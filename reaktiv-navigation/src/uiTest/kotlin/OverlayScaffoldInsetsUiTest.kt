import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTopPositionInRootIsEqualTo
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.waitUntilExactlyOneExists
import androidx.compose.ui.unit.dp
import io.github.syrou.reaktiv.compose.StoreProvider
import io.github.syrou.reaktiv.compose.composeState
import io.github.syrou.reaktiv.core.Store
import io.github.syrou.reaktiv.core.createStore
import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.alias.TitleResource
import io.github.syrou.reaktiv.navigation.createNavigationModule
import io.github.syrou.reaktiv.navigation.definition.Modal
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.extension.navigation
import io.github.syrou.reaktiv.navigation.param.Params
import io.github.syrou.reaktiv.navigation.transition.NavTransition
import io.github.syrou.reaktiv.navigation.ui.NavigationRender
import io.github.syrou.reaktiv.navigation.ui.currentActionResource
import kotlinx.coroutines.launch
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class, ExperimentalMaterial3Api::class)
class OverlayScaffoldInsetsUiTest {

    private object HomeBodyScreen : Screen {
        override val route = "home-body"
        override val enterTransition = NavTransition.None
        override val exitTransition = NavTransition.None

        @Composable
        override fun Content(params: Params) {
            Box(modifier = Modifier.fillMaxSize().testTag("home-body")) { Text("Home body") }
        }
    }

    private object DowngradedModal : Modal {
        override val route = "downgraded"
        override val enterTransition = NavTransition.Fade
        override val exitTransition = NavTransition.Fade

        @Composable
        override fun Content(params: Params) {
            Box(modifier = Modifier.testTag("downgraded-modal")) { Text("Downgraded") }
        }
    }

    private object PickerScreen : Screen {
        override val route = "artists-picker"
        override val enterTransition = NavTransition.SlideUpBottom
        override val exitTransition = NavTransition.SlideOutBottom
        override val swipeToDismiss = true
        override val titleResource: TitleResource = { "Pick artists" }

        @Composable
        override fun Content(params: Params) {
            Box(modifier = Modifier.fillMaxSize().testTag("picker-content")) { Text("Picker body") }
        }
    }

    @Composable
    private fun HomeScaffoldLike(content: @Composable () -> Unit) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = { TopAppBar(title = { Text("Home") }) },
            bottomBar = { NavigationBar { } }
        ) { padding ->
            Box(modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
                content()
            }
        }
    }

    @Composable
    private fun OverlayScaffoldLike(content: @Composable () -> Unit) {
        val navigationState by composeState<NavigationState>()
        val title = navigationState.currentEntry.titleResource?.invoke()
        val actionResource = currentActionResource()
        Scaffold(
            modifier = Modifier
                .consumeWindowInsets(WindowInsets.statusBars)
                .fillMaxSize(),
            topBar = {
                TopAppBar(
                    title = { Text(title ?: "") },
                    actions = { actionResource?.invoke() },
                    modifier = Modifier.testTag("overlay-top-bar")
                )
            }
        ) { padding ->
            Box(modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
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
                    layout { content -> HomeScaffoldLike(content) }
                }
                graph("overlay") {
                    layout { content -> OverlayScaffoldLike(content) }
                    screens(PickerScreen)
                    modals(DowngradedModal)
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

    private fun ComposeUiTest.settleOnPicker(store: Store) {
        awaitCurrentScreen(store, "artists-picker")
        waitUntilExactlyOneExists(hasText("Picker body"), timeoutMillis = UI_TEST_WAIT_MS)
        waitUntil(timeoutMillis = UI_TEST_WAIT_MS) { onAllNodesWithText("Home body").fetchSemanticsNodes().isEmpty() }
        waitForIdle()
    }

    private fun ComposeUiTest.assertOverlayChromeSitsUnderTheStrip() {
        val topBar = onNodeWithTag("overlay-top-bar").getUnclippedBoundsInRoot()
        onNodeWithTag("overlay-top-bar").assertTopPositionInRootIsEqualTo(28.dp)
        onNodeWithTag("picker-content").assertTopPositionInRootIsEqualTo(topBar.bottom)
    }

    @Test
    fun pickerReachedFromScreenLaysOutUnderTheStripWithPill() = runComposeUiTest {
        val store = mount()

        store.launch { store.navigation { navigateTo(PickerScreen) } }
        settleOnPicker(store)

        assertOverlayChromeSitsUnderTheStrip()
        waitUntilExactlyOneExists(hasTestTag("reaktiv-dismiss-indicator"), timeoutMillis = UI_TEST_WAIT_MS)
    }

    @Test
    fun pickerReachedFromModalLaysOutIdenticallyWithoutPill() = runComposeUiTest {
        val store = mount()

        store.launch { store.navigation { navigateTo(DowngradedModal) } }
        awaitCurrentScreen(store, "downgraded")
        waitUntilExactlyOneExists(hasText("Downgraded"), timeoutMillis = UI_TEST_WAIT_MS)

        store.launch { store.navigation { navigateTo<PickerScreen>() } }
        settleOnPicker(store)

        assertOverlayChromeSitsUnderTheStrip()
        waitForIdle()
        onAllNodesWithTag("reaktiv-dismiss-indicator").assertCountEquals(0)
    }
}
