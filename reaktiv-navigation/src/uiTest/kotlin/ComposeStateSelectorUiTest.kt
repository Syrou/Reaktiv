import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.waitUntilExactlyOneExists
import io.github.syrou.reaktiv.compose.StoreProvider
import io.github.syrou.reaktiv.compose.composeState
import io.github.syrou.reaktiv.core.createStore
import io.github.syrou.reaktiv.core.util.selectState
import io.github.syrou.reaktiv.navigation.NavigationAction
import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.ScrubState
import io.github.syrou.reaktiv.navigation.ScrubType
import io.github.syrou.reaktiv.navigation.extension.navigation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class ComposeStateSelectorUiTest {

    @Composable
    private fun BackStackDepth(onComposed: () -> Unit) {
        val depth by composeState<NavigationState, Int> { it.backStack.size }
        SideEffect(onComposed)
        Text("depth:$depth")
    }

    @Composable
    private fun ScrubProgress() {
        val state by composeState<NavigationState>()
        Text("scrub:${state.activeScrub?.progress}")
    }

    @Test
    fun aSelectedValueRecomposesOnlyWhenItChanges() = runComposeUiTest {
        val store = createStore { module(createUiTestModule()) }
        var depthCompositions = 0
        setContent {
            StoreProvider(store) {
                Column {
                    BackStackDepth(onComposed = { depthCompositions++ })
                    ScrubProgress()
                }
            }
        }
        waitUntilExactlyOneExists(hasText("depth:1"), timeoutMillis = UI_TEST_WAIT_MS)
        waitForIdle()
        val settled = depthCompositions

        val home = runBlocking { store.selectState<NavigationState>().first() }.currentEntry
        store.dispatch(NavigationAction.ScrubUpdate(ScrubState(ScrubType.Dismiss, home.stableKey, null, 0.4f)))
        waitUntilExactlyOneExists(hasText("scrub:0.4"), timeoutMillis = UI_TEST_WAIT_MS)
        store.dispatch(NavigationAction.ScrubEnd)
        waitUntilExactlyOneExists(hasText("scrub:null"), timeoutMillis = UI_TEST_WAIT_MS)
        waitForIdle()
        assertEquals(settled, depthCompositions)

        store.launch { store.navigation { navigateTo("ui-detail") } }
        waitUntilExactlyOneExists(hasText("depth:2"), timeoutMillis = UI_TEST_WAIT_MS)
        assertTrue(depthCompositions > settled)
    }

    @Test
    fun aChangedSelectorAppliesWithoutAStateChange() = runComposeUiTest {
        val store = createStore { module(createUiTestModule()) }
        var route by mutableStateOf("ui-home")
        setContent {
            StoreProvider(store) {
                val expected = route
                val isCurrent by composeState<NavigationState, Boolean> { it.currentEntry.route == expected }
                Text("$expected:$isCurrent")
            }
        }
        waitUntilExactlyOneExists(hasText("ui-home:true"), timeoutMillis = UI_TEST_WAIT_MS)

        route = "ui-detail"
        waitUntilExactlyOneExists(hasText("ui-detail:false"), timeoutMillis = UI_TEST_WAIT_MS)
    }

    @Test
    fun aSwappedStoreIsObserved() = runComposeUiTest {
        val first = createStore { module(createUiTestModule()) }
        val second = createStore { module(createUiTestModule()) }
        second.launch { second.navigation { navigateTo("ui-detail") } }
        awaitCurrentScreen(second, "ui-detail")
        var store by mutableStateOf(first)
        setContent {
            StoreProvider(store) {
                val depth by composeState<NavigationState, Int> { it.backStack.size }
                Text("depth:$depth")
            }
        }
        waitUntilExactlyOneExists(hasText("depth:1"), timeoutMillis = UI_TEST_WAIT_MS)

        store = second
        waitUntilExactlyOneExists(hasText("depth:2"), timeoutMillis = UI_TEST_WAIT_MS)
    }
}
