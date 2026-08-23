import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.waitUntilExactlyOneExists
import io.github.syrou.reaktiv.compose.StoreProvider
import io.github.syrou.reaktiv.core.createStore
import io.github.syrou.reaktiv.navigation.createNavigationModule
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.extension.navigation
import io.github.syrou.reaktiv.navigation.param.Params
import io.github.syrou.reaktiv.navigation.transition.NavTransition
import io.github.syrou.reaktiv.navigation.ui.NavigationRender
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class PremountedRevealUiTest {

    private object PlainHostLifecycle {
        var composed = 0
        var disposed = 0

        fun reset() {
            composed = 0
            disposed = 0
        }

        val alive: Int get() = composed - disposed
    }

    private object GraphHostLifecycle {
        var composed = 0
        var disposed = 0

        fun reset() {
            composed = 0
            disposed = 0
        }

        val alive: Int get() = composed - disposed
    }

    private object PlainHostScreen : Screen {
        override val route = "premount-plain-host"
        override val enterTransition = NavTransition.None
        override val exitTransition = NavTransition.None

        @Composable
        override fun Content(params: Params) {
            DisposableEffect(Unit) {
                PlainHostLifecycle.composed++
                onDispose { PlainHostLifecycle.disposed++ }
            }
            Box(modifier = Modifier.fillMaxSize()) {
                Text("Plain Host")
            }
        }
    }

    private object GraphHostScreen : Screen {
        override val route = "premount-graph-host"
        override val enterTransition = NavTransition.None
        override val exitTransition = NavTransition.None

        @Composable
        override fun Content(params: Params) {
            DisposableEffect(Unit) {
                GraphHostLifecycle.composed++
                onDispose { GraphHostLifecycle.disposed++ }
            }
            Box(modifier = Modifier.fillMaxSize()) {
                Text("Graph Host")
            }
        }
    }

    private object PremountSheetScreen : Screen {
        override val route = "premount-sheet"
        override val enterTransition = NavTransition.SlideUpBottom
        override val exitTransition = NavTransition.SlideOutBottom

        @Composable
        override fun Content(params: Params) {
            Column(modifier = Modifier.fillMaxSize()) {
                Text("Premount Sheet")
            }
        }
    }

    @Test
    fun revealedScreenStaysComposedBeneathSheetInSameHierarchy() = runComposeUiTest {
        PlainHostLifecycle.reset()
        val store = createStore {
            module(createNavigationModule {
                rootGraph {
                    start(PlainHostScreen)
                    screens(PlainHostScreen, PremountSheetScreen)
                }
            })
        }
        setContent {
            StoreProvider(store) {
                NavigationRender()
            }
        }
        waitUntilExactlyOneExists(hasText("Plain Host"), timeoutMillis = UI_TEST_WAIT_MS)
        store.launch { store.navigation { navigateTo("premount-sheet") } }
        awaitCurrentScreen(store, "premount-sheet")
        waitUntilExactlyOneExists(hasText("Premount Sheet"), timeoutMillis = UI_TEST_WAIT_MS)
        waitUntil(timeoutMillis = UI_TEST_WAIT_MS) { onAllNodesWithText("Plain Host").fetchSemanticsNodes().isEmpty() }
        waitForIdle()

        assertEquals(0, PlainHostLifecycle.disposed, "Revealed screen must never be disposed while a dismissible sheet covers it")
        assertEquals(1, PlainHostLifecycle.alive, "Revealed screen should be composed exactly once beneath the sheet")
    }

    @Test
    fun revealedHierarchyIsPremountedAtRestAcrossLayoutGraphs() = runComposeUiTest {
        GraphHostLifecycle.reset()
        val store = createStore {
            module(createNavigationModule {
                rootGraph {
                    start("premount-home")
                    screens(PremountSheetScreen)
                    graph("premount-home") {
                        start(GraphHostScreen)
                        layout { content ->
                            Column(modifier = Modifier.fillMaxSize()) {
                                Text("Host Chrome")
                                content()
                            }
                        }
                    }
                }
            })
        }
        setContent {
            StoreProvider(store) {
                NavigationRender()
            }
        }
        waitUntilExactlyOneExists(hasText("Graph Host"), timeoutMillis = UI_TEST_WAIT_MS)
        store.launch { store.navigation { navigateTo("premount-sheet") } }
        awaitCurrentScreen(store, "premount-sheet")
        waitUntilExactlyOneExists(hasText("Premount Sheet"), timeoutMillis = UI_TEST_WAIT_MS)
        waitUntil(timeoutMillis = UI_TEST_WAIT_MS) { onAllNodesWithText("Graph Host").fetchSemanticsNodes().isEmpty() }
        waitForIdle()

        assertEquals(1, GraphHostLifecycle.alive, "Revealed hierarchy should be premounted at rest beneath the sheet")

        onRoot().performTouchInput {
            down(Offset(centerX, 16f))
            repeat(8) {
                moveBy(Offset(0f, height * 0.07f), delayMillis = 30)
            }
            moveBy(Offset(0f, 2f), delayMillis = 100)
            up()
        }
        awaitCurrentScreen(store, "premount-graph-host")
        waitUntilExactlyOneExists(hasText("Graph Host"), timeoutMillis = UI_TEST_WAIT_MS)
        waitUntil(timeoutMillis = UI_TEST_WAIT_MS) { onAllNodesWithText("Premount Sheet").fetchSemanticsNodes().isEmpty() }
        waitForIdle()

        assertEquals(1, GraphHostLifecycle.alive, "Host must be the single live screen after the dismiss commits")
    }
}
