import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.waitUntilDoesNotExist
import androidx.compose.ui.test.waitUntilExactlyOneExists
import androidx.compose.ui.unit.dp
import io.github.syrou.reaktiv.compose.StoreProvider
import io.github.syrou.reaktiv.compose.rememberStore
import io.github.syrou.reaktiv.core.createStore
import io.github.syrou.reaktiv.navigation.createNavigationModule
import io.github.syrou.reaktiv.navigation.definition.LoadingModal
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.extension.navigation
import io.github.syrou.reaktiv.navigation.model.GuardResult
import io.github.syrou.reaktiv.navigation.param.Params
import io.github.syrou.reaktiv.navigation.transition.NavTransition
import io.github.syrou.reaktiv.navigation.ui.NavigationRender
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class ScopedGuardLoadingUiTest {

    private fun screen(name: String, label: String) = object : Screen {
        override val route = name
        override val enterTransition = NavTransition.None
        override val exitTransition = NavTransition.None

        @Composable
        override fun Content(params: Params) {
            Text(label)
        }
    }

    private val loading = object : LoadingModal {
        override val route = "loading"
        override val enterTransition = NavTransition.None
        override val exitTransition = NavTransition.None

        @Composable
        override fun Content(params: Params) {
            Text("Loading")
        }
    }

    private val home = screen("home", "Home")
    private val tabA = screen("tab-a", "Tab A")
    private val tabB = screen("tab-b", "Tab B")
    private val slowTab = screen("slow-tab", "Slow Tab")
    private val slowOutside = screen("slow-outside", "Slow Outside")
    private val gate = CompletableDeferred<GuardResult>()

    private fun module() = createNavigationModule {
        loadingModal(loading)
        rootGraph {
            start(home)
            screens(home)
            graph("tabs") {
                start(tabA)
                layout { content ->
                    val store = rememberStore()
                    Column(modifier = Modifier.fillMaxSize()) {
                        Text(
                            "Bottom Bar",
                            modifier = Modifier.clickable {
                                store.launch { store.navigation { navigateTo("tabs/tab-b") } }
                            }
                        )
                        Box(modifier = Modifier.weight(1f)) { content() }
                    }
                }
                screens(tabA, tabB)
                intercept(guard = { _ -> gate.await() }) {
                    screens(slowTab)
                }
            }
            intercept(guard = { _ -> gate.await() }) {
                screens(slowOutside)
            }
        }
    }

    @Test
    fun a_slow_guard_inside_a_layout_shows_loading_in_the_content_and_keeps_the_chrome() = runComposeUiTest {
        val store = createStore { module(module()) }
        setContent { StoreProvider(store) { NavigationRender() } }
        store.launch { store.navigation { navigateTo("tabs") } }
        waitUntilExactlyOneExists(hasText("Tab A"), timeoutMillis = UI_TEST_WAIT_MS)

        store.launch { store.navigation { navigateTo("tabs/slow-tab") } }
        waitUntilExactlyOneExists(hasText("Loading"), timeoutMillis = UI_TEST_WAIT_MS)

        val bar = onNodeWithText("Bottom Bar").getBoundsInRoot()
        val overlay = onNodeWithText("Loading").getBoundsInRoot()
        assertTrue(overlay.top >= bar.bottom, "the loading modal sits in the layout's content, below its chrome")

        gate.complete(GuardResult.Allow)
        waitUntilExactlyOneExists(hasText("Slow Tab"), timeoutMillis = UI_TEST_WAIT_MS)
        waitUntilDoesNotExist(hasText("Loading"), timeoutMillis = UI_TEST_WAIT_MS)
    }

    @Test
    fun a_slow_guard_toward_another_part_of_the_app_covers_the_whole_app() = runComposeUiTest {
        val store = createStore { module(module()) }
        setContent { StoreProvider(store) { NavigationRender() } }
        store.launch { store.navigation { navigateTo("tabs") } }
        waitUntilExactlyOneExists(hasText("Tab A"), timeoutMillis = UI_TEST_WAIT_MS)

        store.launch { store.navigation { navigateTo("slow-outside") } }
        waitUntilExactlyOneExists(hasText("Loading"), timeoutMillis = UI_TEST_WAIT_MS)

        assertEquals(0.dp, onNodeWithText("Loading").getBoundsInRoot().top)
        gate.complete(GuardResult.Allow)
        waitUntilExactlyOneExists(hasText("Slow Outside"), timeoutMillis = UI_TEST_WAIT_MS)
    }

    @Test
    fun tapping_the_chrome_while_a_section_loads_cancels_it() = runComposeUiTest {
        val store = createStore { module(module()) }
        setContent { StoreProvider(store) { NavigationRender() } }
        store.launch { store.navigation { navigateTo("tabs") } }
        waitUntilExactlyOneExists(hasText("Tab A"), timeoutMillis = UI_TEST_WAIT_MS)

        store.launch { store.navigation { navigateTo("tabs/slow-tab") } }
        waitUntilExactlyOneExists(hasText("Loading"), timeoutMillis = UI_TEST_WAIT_MS)

        onNodeWithText("Bottom Bar").performClick()
        waitUntilExactlyOneExists(hasText("Tab B"), timeoutMillis = UI_TEST_WAIT_MS)
        waitUntilDoesNotExist(hasText("Loading"), timeoutMillis = UI_TEST_WAIT_MS)
        gate.complete(GuardResult.Allow)
        waitForIdle()
        onNodeWithText("Slow Tab").assertDoesNotExist()
    }
}
