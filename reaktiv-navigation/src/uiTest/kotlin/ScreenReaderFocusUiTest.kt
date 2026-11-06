import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.waitUntilExactlyOneExists
import androidx.compose.ui.unit.dp
import io.github.syrou.reaktiv.compose.StoreProvider
import io.github.syrou.reaktiv.core.Store
import io.github.syrou.reaktiv.core.createStore
import io.github.syrou.reaktiv.core.util.selectState
import io.github.syrou.reaktiv.navigation.NavigationModule
import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.alias.TitleResource
import io.github.syrou.reaktiv.navigation.createNavigationModule
import io.github.syrou.reaktiv.navigation.definition.DismissSource
import io.github.syrou.reaktiv.navigation.definition.Dismissal
import io.github.syrou.reaktiv.navigation.definition.Modal
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.extension.navigation
import io.github.syrou.reaktiv.navigation.param.Params
import io.github.syrou.reaktiv.navigation.transition.NavTransition
import io.github.syrou.reaktiv.navigation.ui.LocalScreenReaderFocus
import io.github.syrou.reaktiv.navigation.ui.NavigationRender
import io.github.syrou.reaktiv.navigation.ui.ScreenReaderFocus
import io.github.syrou.reaktiv.navigation.ui.ScreenReaderFocusTarget
import io.github.syrou.reaktiv.navigation.util.performUserBack
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class ScreenReaderFocusUiTest {

    private object HomeScreen : Screen {
        override val route = "home"
        override val enterTransition = NavTransition.None
        override val exitTransition = NavTransition.None

        @Composable
        override fun Content(params: Params) {
            Box(modifier = Modifier.fillMaxSize()) { Text("Home") }
        }
    }

    private object OtherScreen : Screen {
        override val route = "other"
        override val enterTransition = NavTransition.None
        override val exitTransition = NavTransition.None

        @Composable
        override fun Content(params: Params) {
            Box(modifier = Modifier.fillMaxSize()) { Text("Other") }
        }
    }

    private object PickerModal : Modal {
        override val route = "picker"
        override val enterTransition = NavTransition.None
        override val exitTransition = NavTransition.None
        override val dismissal = Dismissal.Dismissable
        override val dismissLabel: TitleResource = { "Close" }

        @Composable
        override fun Content(params: Params) {
            Box(modifier = Modifier.size(120.dp).background(Color.White)) { Text("Picker") }
        }
    }

    private object SlidingModal : Modal {
        override val route = "sliding"
        override val enterTransition = NavTransition.SlideUpBottom
        override val exitTransition = NavTransition.SlideOutBottom
        override val dismissal = Dismissal.Dismissable
        override val dismissLabel: TitleResource = { "Close" }

        @Composable
        override fun Content(params: Params) {
            Box(modifier = Modifier.size(120.dp).background(Color.White)) { Text("Sliding") }
        }
    }

    private object Opener : ScreenReaderFocusTarget

    private class RecordingFocus : ScreenReaderFocus {
        var focused: ScreenReaderFocusTarget? = Opener
        val moves = mutableListOf<ScreenReaderFocusTarget>()

        override fun focusedNode(): ScreenReaderFocusTarget? = focused

        override fun moveTo(node: ScreenReaderFocusTarget) {
            moves += node
        }
    }

    private class Harness(val store: Store, val navModule: NavigationModule, val focus: RecordingFocus)

    private fun ComposeUiTest.mount(): Harness {
        val navModule = createNavigationModule {
            rootGraph {
                start(HomeScreen)
                screens(HomeScreen, OtherScreen)
                modals(PickerModal, SlidingModal)
            }
        }
        val store = createStore { module(navModule) }
        val focus = RecordingFocus()
        setContent {
            StoreProvider(store) {
                CompositionLocalProvider(LocalScreenReaderFocus provides focus) {
                    NavigationRender()
                }
            }
        }
        waitUntilExactlyOneExists(hasText("Home"), timeoutMillis = UI_TEST_WAIT_MS)
        return Harness(store, navModule, focus)
    }

    private fun ComposeUiTest.open(harness: Harness, modal: Modal) {
        harness.store.launch { harness.store.navigation { navigateTo(modal) } }
        awaitCurrentScreen(harness.store, modal.route)
        waitForIdle()
    }

    private fun ComposeUiTest.pressSystemBack(harness: Harness) {
        harness.store.launch { performUserBack(harness.store, harness.navModule, DismissSource.Back) }
        awaitCurrentScreen(harness.store, "home")
        waitForIdle()
    }

    @Test
    fun closingAModalBackToItsOpenerRestoresFocus() = runComposeUiTest {
        val harness = mount()
        open(harness, PickerModal)

        pressSystemBack(harness)

        assertEquals(listOf<ScreenReaderFocusTarget>(Opener), harness.focus.moves)
    }

    @Test
    fun aModalThatNavigatesElsewhereLeavesFocusAlone() = runComposeUiTest {
        val harness = mount()
        open(harness, PickerModal)

        harness.store.launch {
            harness.store.navigation {
                navigateBack()
                navigateTo(OtherScreen)
            }
        }
        awaitCurrentScreen(harness.store, "other")
        waitForIdle()

        assertEquals(emptyList(), harness.focus.moves)
    }

    @Test
    fun noScreenReaderFocusMeansNothingToRestore() = runComposeUiTest {
        val harness = mount()
        harness.focus.focused = null
        open(harness, PickerModal)

        pressSystemBack(harness)

        assertEquals(emptyList(), harness.focus.moves)
    }

    @Test
    fun aSlidingModalStaysOutOfTheSemanticsTreeUntilItHasEntered() = runComposeUiTest {
        val harness = mount()
        mainClock.autoAdvance = false
        harness.store.launch { harness.store.navigation { navigateTo(SlidingModal) } }
        waitUntil(timeoutMillis = UI_TEST_WAIT_MS) {
            runBlocking { harness.store.selectState<NavigationState>() }.value.currentEntry.route == "sliding"
        }
        repeat(3) { mainClock.advanceTimeBy(16) }

        onAllNodes(hasText("Sliding")).assertCountEquals(0)
        onAllNodes(hasContentDescription("Close")).assertCountEquals(0)
        onAllNodes(hasText("Home")).assertCountEquals(1)

        mainClock.advanceTimeBy(2_000)
        mainClock.autoAdvance = true
        waitForIdle()

        onAllNodes(hasText("Sliding")).assertCountEquals(1)
        onAllNodes(hasContentDescription("Close")).assertCountEquals(1)
    }
}
