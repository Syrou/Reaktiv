import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.waitUntilExactlyOneExists
import io.github.syrou.reaktiv.compose.StoreProvider
import io.github.syrou.reaktiv.core.createStore
import io.github.syrou.reaktiv.navigation.extension.navigateBack
import io.github.syrou.reaktiv.navigation.extension.navigation
import io.github.syrou.reaktiv.navigation.ui.NavigationRender
import kotlinx.coroutines.launch
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class TimedTransitionRegressionUiTest {

    @Test
    fun timedPushKeepsPreviousComposedThenDisposesIt() = runComposeUiTest {
        val store = createStore {
            module(createUiTestModule())
        }
        setContent {
            StoreProvider(store) {
                NavigationRender()
            }
        }
        waitUntilExactlyOneExists(hasText("UI Home"), timeoutMillis = UI_TEST_WAIT_MS)

        store.launch {
            store.navigation { navigateTo("ui-detail") }
        }
        waitUntilExactlyOneExists(hasText("UI Detail"), timeoutMillis = UI_TEST_WAIT_MS)
        waitUntil(timeoutMillis = UI_TEST_WAIT_MS) {
            onAllNodesWithText("UI Home").fetchSemanticsNodes().isEmpty()
        }
    }

    @Test
    fun timedPopRestoresPreviousAndDisposesPopped() = runComposeUiTest {
        val store = createStore {
            module(createUiTestModule())
        }
        setContent {
            StoreProvider(store) {
                NavigationRender()
            }
        }
        waitUntilExactlyOneExists(hasText("UI Home"), timeoutMillis = UI_TEST_WAIT_MS)

        store.launch {
            store.navigation { navigateTo("ui-detail") }
        }
        waitUntilExactlyOneExists(hasText("UI Detail"), timeoutMillis = UI_TEST_WAIT_MS)

        store.launch {
            store.navigateBack()
        }
        waitUntilExactlyOneExists(hasText("UI Home"), timeoutMillis = UI_TEST_WAIT_MS)
        waitUntil(timeoutMillis = UI_TEST_WAIT_MS) {
            onAllNodesWithText("UI Detail").fetchSemanticsNodes().isEmpty()
        }
    }

    @Test
    fun noneEnterTransitionStillSwitchesScreens() = runComposeUiTest {
        val store = createStore {
            module(createUiTestModule())
        }
        setContent {
            StoreProvider(store) {
                NavigationRender()
            }
        }
        waitUntilExactlyOneExists(hasText("UI Home"), timeoutMillis = UI_TEST_WAIT_MS)

        store.launch {
            store.navigation { navigateTo("ui-plain") }
        }
        waitUntilExactlyOneExists(hasText("UI Plain"), timeoutMillis = UI_TEST_WAIT_MS)
        waitUntil(timeoutMillis = UI_TEST_WAIT_MS) {
            onAllNodesWithText("UI Home").fetchSemanticsNodes().isEmpty()
        }
    }
}
