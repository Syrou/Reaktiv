import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
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
class ComposeHarnessSmokeTest {

    @Test
    fun navigationRenderShowsStartScreen() = runComposeUiTest {
        val store = createStore {
            module(createUiTestModule())
        }
        setContent {
            StoreProvider(store) {
                NavigationRender()
            }
        }
        waitUntilExactlyOneExists(hasText("UI Home"), timeoutMillis = UI_TEST_WAIT_MS)
    }

    @Test
    fun programmaticNavigationUpdatesUi() = runComposeUiTest {
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
        onNodeWithText("UI Detail").assertDoesNotExist()
    }
}
