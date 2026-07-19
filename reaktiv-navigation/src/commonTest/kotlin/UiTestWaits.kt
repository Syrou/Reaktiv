import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.waitUntilExactlyOneExists
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

@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.scrollListUntilTextVisible(
    text: String,
    revealLater: Boolean,
    maxSwipes: Int = 40
) {
    val step = if (revealLater) -0.05f else 0.05f
    val settle = if (revealLater) -2f else 2f
    val startFraction = if (revealLater) 0.65f else 0.35f
    repeat(maxSwipes) {
        if (onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()) {
            waitForIdle()
            return
        }
        onRoot().performTouchInput {
            down(Offset(centerX, height * startFraction))
            repeat(4) {
                moveBy(Offset(0f, height * step), delayMillis = 30)
            }
            moveBy(Offset(0f, settle), delayMillis = 100)
            up()
        }
        waitForIdle()
    }
    waitUntilExactlyOneExists(hasText(text), timeoutMillis = UI_TEST_WAIT_MS)
}
