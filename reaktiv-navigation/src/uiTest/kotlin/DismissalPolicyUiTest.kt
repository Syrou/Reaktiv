import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.waitUntilExactlyOneExists
import androidx.compose.ui.unit.dp
import io.github.syrou.reaktiv.compose.StoreProvider
import io.github.syrou.reaktiv.core.Store
import io.github.syrou.reaktiv.core.StoreAccessor
import io.github.syrou.reaktiv.core.createStore
import io.github.syrou.reaktiv.core.util.selectState
import io.github.syrou.reaktiv.navigation.NavigationModule
import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.createNavigationModule
import io.github.syrou.reaktiv.navigation.definition.Dismissal
import io.github.syrou.reaktiv.navigation.definition.Modal
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.extension.navigation
import io.github.syrou.reaktiv.navigation.param.Params
import io.github.syrou.reaktiv.navigation.transition.NavTransition
import io.github.syrou.reaktiv.navigation.ui.NavigationRender
import io.github.syrou.reaktiv.navigation.ui.dispatchBackDismissal
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class DismissalPolicyUiTest {

    private object HomeScreen : Screen {
        override val route = "home"
        override val enterTransition = NavTransition.None
        override val exitTransition = NavTransition.None

        @Composable
        override fun Content(params: Params) {
            Box(modifier = Modifier.fillMaxSize().testTag("home")) { Text("Home") }
        }
    }

    private abstract class SmallModal(override val route: String) : Modal {
        override val enterTransition = NavTransition.None
        override val exitTransition = NavTransition.None

        @Composable
        override fun Content(params: Params) {
            Box(modifier = Modifier.size(120.dp).background(Color.White).testTag(route)) { Text(route) }
        }
    }

    private object BlockingModal : SmallModal("blocking-modal") {
        override val dismissal = Dismissal.Blocking
    }

    private object DismissableModal : SmallModal("dismissable-modal") {
        override val dismissal = Dismissal.Dismissable
    }

    private object LegacyLockedModal : SmallModal("legacy-locked-modal") {
        @Suppress("OVERRIDE_DEPRECATION")
        override val onDismissRequest: (suspend StoreAccessor.() -> Unit)? = { }
    }

    private object LegacyDefaultModal : SmallModal("legacy-default-modal")

    private fun buildModule(): NavigationModule = createNavigationModule {
        rootGraph {
            start(HomeScreen)
            screens(HomeScreen)
            modals(BlockingModal, DismissableModal, LegacyLockedModal, LegacyDefaultModal)
        }
    }

    private class Harness(val store: Store, val navModule: NavigationModule)

    private fun ComposeUiTest.mount(): Harness {
        val navModule = buildModule()
        val store = createStore { module(navModule) }
        setContent {
            StoreProvider(store) {
                NavigationRender()
            }
        }
        waitUntilExactlyOneExists(hasText("Home"), timeoutMillis = UI_TEST_WAIT_MS)
        return Harness(store, navModule)
    }

    private fun ComposeUiTest.open(harness: Harness, modal: Modal) {
        harness.store.launch { harness.store.navigation { navigateTo(modal) } }
        awaitCurrentScreen(harness.store, modal.route)
        waitUntilExactlyOneExists(hasText(modal.route), timeoutMillis = UI_TEST_WAIT_MS)
        waitForIdle()
    }

    private fun ComposeUiTest.pressSystemBack(harness: Harness) {
        harness.store.launch { dispatchBackDismissal(harness.store, harness.navModule) }
        waitForIdle()
    }

    private fun ComposeUiTest.tapOutside() {
        onRoot().performTouchInput { click(Offset(8f, 8f)) }
        waitForIdle()
    }

    private fun currentRoute(harness: Harness): String =
        runBlocking { harness.store.selectState<NavigationState>() }.value.currentEntry.route

    @Test
    fun blockingModalIgnoresBackAndTapOutside() = runComposeUiTest {
        val harness = mount()
        open(harness, BlockingModal)

        pressSystemBack(harness)
        assertEquals("blocking-modal", currentRoute(harness))

        tapOutside()
        assertEquals("blocking-modal", currentRoute(harness))
    }

    @Test
    fun dismissableModalPopsOnBack() = runComposeUiTest {
        val harness = mount()
        open(harness, DismissableModal)

        pressSystemBack(harness)
        awaitCurrentScreen(harness.store, "home")
    }

    @Test
    fun dismissableModalPopsOnTapOutside() = runComposeUiTest {
        val harness = mount()
        open(harness, DismissableModal)

        tapOutside()
        awaitCurrentScreen(harness.store, "home")
    }

    @Test
    fun legacyNoOpHandlerStillBlocksBack() = runComposeUiTest {
        val harness = mount()
        open(harness, LegacyLockedModal)

        pressSystemBack(harness)
        assertEquals("legacy-locked-modal", currentRoute(harness))
    }

    @Test
    fun legacyDefaultPopsOnBackAndIgnoresTapOutside() = runComposeUiTest {
        val harness = mount()
        open(harness, LegacyDefaultModal)

        tapOutside()
        assertEquals("legacy-default-modal", currentRoute(harness))

        pressSystemBack(harness)
        awaitCurrentScreen(harness.store, "home")
    }
}
