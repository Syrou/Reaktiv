import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.waitUntilExactlyOneExists
import io.github.syrou.reaktiv.compose.StoreProvider
import io.github.syrou.reaktiv.core.createStore
import io.github.syrou.reaktiv.core.util.selectState
import io.github.syrou.reaktiv.navigation.NavigationAction
import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.ScrubState
import io.github.syrou.reaktiv.navigation.extension.navigation
import io.github.syrou.reaktiv.navigation.ui.NavigationRender
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class StateScrubUiTest {

    @Test
    fun projectedScrubStateDrivesFollowerCompositionWithoutDispatch() = runComposeUiTest {
        val store = createStore { module(createUiTestModule()) }
        setContent {
            StoreProvider(store) {
                NavigationRender()
            }
        }
        waitUntilExactlyOneExists(hasText("UI Home"), timeoutMillis = UI_TEST_WAIT_MS)
        store.launch { store.navigation { navigateTo("ui-detail") } }
        awaitCurrentScreen(store, "ui-detail")
        waitUntilExactlyOneExists(hasText("UI Detail"), timeoutMillis = UI_TEST_WAIT_MS)
        waitUntil(timeoutMillis = UI_TEST_WAIT_MS) {
            onAllNodesWithText("UI Home").fetchSemanticsNodes().isEmpty()
        }

        val state = runBlocking { store.selectState<NavigationState>().first() }
        val top = state.orderedBackStack.last()
        val revealed = state.orderedBackStack[state.orderedBackStack.size - 2]
        val scrub = ScrubState("back-scrub", top.stableKey, revealed.stableKey, 0.4f)

        store.dispatch(NavigationAction.ScrubUpdate(scrub))
        waitUntilExactlyOneExists(hasText("UI Home"), timeoutMillis = UI_TEST_WAIT_MS)
        onNodeWithText("UI Detail").assertExists()
        assertEquals("ui-detail", runBlocking { store.selectState<NavigationState>().first() }.currentEntry.route)

        store.dispatch(NavigationAction.ScrubEnd)
        waitUntil(timeoutMillis = UI_TEST_WAIT_MS) {
            onAllNodesWithText("UI Home").fetchSemanticsNodes().isEmpty()
        }
        onNodeWithText("UI Detail").assertExists()
    }

    @Test
    fun leaderScrubMirrorsIntoNavigationState() = runComposeUiTest {
        val store = createStore { module(createUiTestModule()) }
        setContent {
            StoreProvider(store) {
                NavigationRender()
            }
        }
        waitUntilExactlyOneExists(hasText("UI Home"), timeoutMillis = UI_TEST_WAIT_MS)
        store.launch { store.navigation { navigateTo("ui-detail") } }
        awaitCurrentScreen(store, "ui-detail")
        waitUntilExactlyOneExists(hasText("UI Detail"), timeoutMillis = UI_TEST_WAIT_MS)

        onRoot().performTouchInput {
            down(Offset(10f, centerY))
            moveBy(Offset(60f, 0f))
            moveBy(Offset(80f, 0f))
        }
        val navigationState = runBlocking { store.selectState<NavigationState>() }
        waitUntil(timeoutMillis = UI_TEST_WAIT_MS) {
            navigationState.value.activeScrub?.kind == "back-scrub"
        }
        assertTrue(navigationState.value.activeScrub!!.progress > 0f)

        onRoot().performTouchInput {
            moveBy(Offset(2f, 0f), delayMillis = 100)
            up()
        }
        waitUntil(timeoutMillis = UI_TEST_WAIT_MS) { navigationState.value.activeScrub == null }
        assertEquals("ui-detail", navigationState.value.currentEntry.route)
    }

    @Test
    fun committedScrubClearsViaBackstackChangeAndSettlesCleanly() = runComposeUiTest {
        val store = createStore { module(createUiTestModule()) }
        setContent {
            StoreProvider(store) {
                NavigationRender()
            }
        }
        waitUntilExactlyOneExists(hasText("UI Home"), timeoutMillis = UI_TEST_WAIT_MS)
        store.launch { store.navigation { navigateTo("ui-detail") } }
        awaitCurrentScreen(store, "ui-detail")
        waitUntilExactlyOneExists(hasText("UI Detail"), timeoutMillis = UI_TEST_WAIT_MS)

        val state = runBlocking { store.selectState<NavigationState>().first() }
        val top = state.orderedBackStack.last()
        val revealed = state.orderedBackStack[state.orderedBackStack.size - 2]
        store.dispatch(
            NavigationAction.ScrubUpdate(ScrubState("back-scrub", top.stableKey, revealed.stableKey, 0.9f))
        )
        waitUntilExactlyOneExists(hasText("UI Home"), timeoutMillis = UI_TEST_WAIT_MS)

        store.dispatch(NavigationAction.Back)
        awaitCurrentScreen(store, "ui-home")
        waitUntil(timeoutMillis = UI_TEST_WAIT_MS) {
            onAllNodesWithText("UI Detail").fetchSemanticsNodes().isEmpty()
        }
        onNodeWithText("UI Home").assertExists()
        assertNull(runBlocking { store.selectState<NavigationState>().first() }.activeScrub)
    }
}
