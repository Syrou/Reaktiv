import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import io.github.syrou.reaktiv.core.Store
import io.github.syrou.reaktiv.core.util.selectState
import io.github.syrou.reaktiv.navigation.NavigationState
import kotlinx.coroutines.runBlocking

internal const val UI_TEST_WAIT_MS: Long = 30_000

@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.awaitCurrentScreen(store: Store, route: String) {
    val expected = route.substringAfterLast('/')
    val navigationState = runBlocking { store.selectState<NavigationState>() }
    waitUntil(timeoutMillis = UI_TEST_WAIT_MS) {
        navigationState.value.currentEntry.navigatable.route == expected
    }
    waitForIdle()
}
