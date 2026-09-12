import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertTopPositionInRootIsEqualTo
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.waitUntilExactlyOneExists
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import io.github.syrou.reaktiv.compose.StoreProvider
import io.github.syrou.reaktiv.core.createStore
import io.github.syrou.reaktiv.navigation.createNavigationModule
import io.github.syrou.reaktiv.navigation.definition.ContentInsets
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.extension.navigation
import io.github.syrou.reaktiv.navigation.param.Params
import io.github.syrou.reaktiv.navigation.transition.NavTransition
import io.github.syrou.reaktiv.navigation.ui.NavigationRender
import kotlinx.coroutines.launch
import kotlin.test.Test

/**
 * The inset values themselves only exist on a device, so what is checked here is that declaring
 * them costs a screen nothing when there are none: a safe-area screen occupies the window exactly
 * as a fullscreen one does, which is what says the wrapper applying the declaration is transparent.
 */
@OptIn(ExperimentalTestApi::class)
class ContentInsetsUiTest {

    private object FullscreenScreen : Screen {
        override val route = "insets-fullscreen"
        override val enterTransition = NavTransition.None
        override val exitTransition = NavTransition.None

        @Composable
        override fun Content(params: Params) {
            Box(modifier = Modifier.fillMaxSize().testTag("insets-fullscreen-body")) {
                Text("Fullscreen Body")
            }
        }
    }

    private object SafeAreaScreen : Screen {
        override val route = "insets-safe-area"
        override val enterTransition = NavTransition.None
        override val exitTransition = NavTransition.None
        override val contentInsets = ContentInsets.SafeArea

        @Composable
        override fun Content(params: Params) {
            Box(modifier = Modifier.fillMaxSize().testTag("insets-safe-area-body")) {
                Text("Safe Area Body")
            }
        }
    }

    private fun module() = createNavigationModule {
        rootGraph {
            start(FullscreenScreen)
            screens(FullscreenScreen, SafeAreaScreen)
        }
    }

    @Test
    fun a_safe_area_screen_fills_the_window_when_there_are_no_insets() = runComposeUiTest {
        val store = createStore { module(module()) }
        setContent { StoreProvider(store) { NavigationRender() } }
        waitUntilExactlyOneExists(hasText("Fullscreen Body"), timeoutMillis = UI_TEST_WAIT_MS)
        waitForIdle()

        val window = onRoot().getUnclippedBoundsInRoot()
        onNodeWithTag("insets-fullscreen-body").assertTopPositionInRootIsEqualTo(0.dp)
        onNodeWithTag("insets-fullscreen-body").assertHeightIsEqualTo(window.height)

        store.launch { store.navigation { navigateTo("insets-safe-area") } }
        awaitCurrentScreen(store, "insets-safe-area")
        waitUntilExactlyOneExists(hasText("Safe Area Body"), timeoutMillis = UI_TEST_WAIT_MS)
        waitForIdle()

        onNodeWithTag("insets-safe-area-body").assertTopPositionInRootIsEqualTo(0.dp)
        onNodeWithTag("insets-safe-area-body").assertHeightIsEqualTo(window.height)
    }
}
