import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.waitUntilExactlyOneExists
import io.github.syrou.reaktiv.compose.StoreProvider
import io.github.syrou.reaktiv.compose.composeState
import io.github.syrou.reaktiv.core.Store
import io.github.syrou.reaktiv.core.createStore
import io.github.syrou.reaktiv.core.util.selectState
import io.github.syrou.reaktiv.navigation.NavigationAction
import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.ScrubState
import io.github.syrou.reaktiv.navigation.ScrubType
import io.github.syrou.reaktiv.navigation.alias.TitleResource
import io.github.syrou.reaktiv.navigation.createNavigationModule
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.extension.navigateBack
import io.github.syrou.reaktiv.navigation.extension.navigation
import io.github.syrou.reaktiv.navigation.param.Params
import io.github.syrou.reaktiv.navigation.transition.NavTransition
import io.github.syrou.reaktiv.navigation.ui.currentTitle
import io.github.syrou.reaktiv.navigation.ui.previousTitle
import io.github.syrou.reaktiv.navigation.ui.rememberNavigationChrome
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class NavigationChromeRecompositionUiTest {

    private fun titled(route: String, title: String) = object : Screen {
        override val route = route
        override val enterTransition = NavTransition.None
        override val exitTransition = NavTransition.None
        override val titleResource: TitleResource = { title }

        @Composable
        override fun Content(params: Params) { Text(route) }
    }

    private fun titledStore(): Store {
        val home = titled("home", "Home")
        val detail = titled("detail", "Detail")
        return createStore {
            module(createNavigationModule {
                rootGraph {
                    start(home)
                    screens(home, detail)
                }
            })
        }
    }

    @Composable
    private fun TitleHeader(onComposed: () -> Unit) {
        val title = currentTitle()
        val back = previousTitle()
        SideEffect(onComposed)
        Text("title:$title back:$back")
    }

    @Composable
    private fun ChromeHeader(onComposed: () -> Unit) {
        val chrome = rememberNavigationChrome()
        SideEffect(onComposed)
        Text("title:${chrome.title} back:${chrome.backTitle} canGoBack:${chrome.canGoBack}")
    }

    @Composable
    private fun ScrubProgress() {
        val state by composeState<NavigationState>()
        Text("scrub:${state.activeScrub?.progress}")
    }

    private fun ComposeUiTest.scrubBackAndRelease(store: Store) {
        val state = runBlocking { store.selectState<NavigationState>().first() }
        val top = state.currentEntry.stableKey
        val revealed = state.revealedEntry?.stableKey
        listOf(0.2f, 0.4f, 0.6f).forEach { progress ->
            store.dispatch(NavigationAction.ScrubUpdate(ScrubState(ScrubType.Back, top, revealed, progress)))
            waitUntilExactlyOneExists(hasText("scrub:$progress"), timeoutMillis = UI_TEST_WAIT_MS)
        }
        store.dispatch(NavigationAction.ScrubEnd)
        waitUntilExactlyOneExists(hasText("scrub:null"), timeoutMillis = UI_TEST_WAIT_MS)
        waitForIdle()
    }

    @Test
    fun titleHelpersIgnoreScrubFramesAndFollowTheCurrentEntry() = runComposeUiTest {
        val store = titledStore()
        var compositions = 0
        setContent {
            StoreProvider(store) {
                Column {
                    TitleHeader(onComposed = { compositions++ })
                    ScrubProgress()
                }
            }
        }
        waitUntilExactlyOneExists(hasText("title:Home back:null"), timeoutMillis = UI_TEST_WAIT_MS)
        store.launch { store.navigation { navigateTo("detail") } }
        waitUntilExactlyOneExists(hasText("title:Detail back:Home"), timeoutMillis = UI_TEST_WAIT_MS)
        waitForIdle()
        val settled = compositions

        scrubBackAndRelease(store)
        assertEquals(settled, compositions)

        store.launch { store.navigateBack() }
        waitUntilExactlyOneExists(hasText("title:Home back:null"), timeoutMillis = UI_TEST_WAIT_MS)
        assertTrue(compositions > settled)
    }

    @Test
    fun navigationChromeIgnoresScrubFramesAndFollowsTheCurrentEntry() = runComposeUiTest {
        val store = titledStore()
        var compositions = 0
        setContent {
            StoreProvider(store) {
                Column {
                    ChromeHeader(onComposed = { compositions++ })
                    ScrubProgress()
                }
            }
        }
        waitUntilExactlyOneExists(hasText("title:Home back:null canGoBack:false"), timeoutMillis = UI_TEST_WAIT_MS)
        store.launch { store.navigation { navigateTo("detail") } }
        waitUntilExactlyOneExists(hasText("title:Detail back:Home canGoBack:true"), timeoutMillis = UI_TEST_WAIT_MS)
        waitForIdle()
        val settled = compositions

        scrubBackAndRelease(store)
        assertEquals(settled, compositions)

        store.launch { store.navigateBack() }
        waitUntilExactlyOneExists(hasText("title:Home back:null canGoBack:false"), timeoutMillis = UI_TEST_WAIT_MS)
        assertTrue(compositions > settled)
    }
}
