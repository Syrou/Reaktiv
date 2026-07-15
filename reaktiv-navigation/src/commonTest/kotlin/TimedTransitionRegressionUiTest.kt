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
        waitUntilExactlyOneExists(hasText("UI Home"), timeoutMillis = 5000)

        store.launch {
            store.navigation { navigateTo("ui-detail") }
        }
        waitUntilExactlyOneExists(hasText("UI Detail"), timeoutMillis = 5000)
        waitUntil(timeoutMillis = 5000) {
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
        waitUntilExactlyOneExists(hasText("UI Home"), timeoutMillis = 5000)

        store.launch {
            store.navigation { navigateTo("ui-detail") }
        }
        waitUntilExactlyOneExists(hasText("UI Detail"), timeoutMillis = 5000)

        store.launch {
            store.navigateBack()
        }
        waitUntilExactlyOneExists(hasText("UI Home"), timeoutMillis = 5000)
        waitUntil(timeoutMillis = 5000) {
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
        waitUntilExactlyOneExists(hasText("UI Home"), timeoutMillis = 5000)

        store.launch {
            store.navigation { navigateTo("ui-plain") }
        }
        waitUntilExactlyOneExists(hasText("UI Plain"), timeoutMillis = 5000)
        waitUntil(timeoutMillis = 5000) {
            onAllNodesWithText("UI Home").fetchSemanticsNodes().isEmpty()
        }
    }
}
