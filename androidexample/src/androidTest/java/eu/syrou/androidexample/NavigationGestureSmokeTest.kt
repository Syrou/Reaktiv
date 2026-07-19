package eu.syrou.androidexample

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.syrou.reaktiv.compose.StoreProvider
import io.github.syrou.reaktiv.core.Store
import io.github.syrou.reaktiv.core.createStore
import io.github.syrou.reaktiv.navigation.createNavigationModule
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.extension.navigation
import io.github.syrou.reaktiv.navigation.param.Params
import io.github.syrou.reaktiv.navigation.transition.NavTransition
import io.github.syrou.reaktiv.navigation.ui.NavigationRender
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NavigationGestureSmokeTest {

    @get:Rule
    val composeRule = createComposeRule()

    private object RefreshCounter {
        val count = mutableIntStateOf(0)
    }

    private object SmokeHostScreen : Screen {
        override val route = "smoke-host"
        override val enterTransition = NavTransition.None
        override val exitTransition = NavTransition.None

        @Composable
        override fun Content(params: Params) {
            Text("Smoke Host")
        }
    }

    private object SmokeSheetScreen : Screen {
        override val route = "smoke-sheet"
        override val enterTransition = NavTransition.SlideUpBottom
        override val exitTransition = NavTransition.SlideOutBottom

        @OptIn(ExperimentalMaterial3Api::class)
        @Composable
        override fun Content(params: Params) {
            PullToRefreshBox(
                isRefreshing = false,
                onRefresh = { RefreshCounter.count.intValue++ },
                modifier = Modifier.fillMaxSize()
            ) {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(40) { index ->
                        Text(
                            text = "Smoke Row $index",
                            modifier = Modifier.height(48.dp)
                        )
                    }
                }
            }
        }
    }

    private fun buildStore(): Store = createStore {
        module(createNavigationModule {
            rootGraph {
                start("smoke-home")
                screens(SmokeSheetScreen)
                graph("smoke-home") {
                    start(SmokeHostScreen)
                    layout { content ->
                        Column(modifier = Modifier.fillMaxSize()) {
                            Text(
                                text = "Smoke Chrome",
                                modifier = Modifier.padding(8.dp)
                            )
                            content()
                        }
                    }
                }
            }
        })
    }

    private fun openSheet(store: Store) {
        composeRule.setContent {
            StoreProvider(store) {
                NavigationRender()
            }
        }
        composeRule.waitUntil(30_000) {
            composeRule.onAllNodesWithText("Smoke Host").fetchSemanticsNodes().isNotEmpty()
        }
        store.launch { store.navigation { navigateTo("smoke-sheet") } }
        composeRule.waitUntil(30_000) {
            composeRule.onAllNodesWithText("Smoke Row 0").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitUntil(30_000) {
            composeRule.onAllNodesWithText("Smoke Host").fetchSemanticsNodes().isEmpty()
        }
        composeRule.waitForIdle()
    }

    private fun scrollUntilTextVisible(text: String, maxSwipes: Int = 40) {
        repeat(maxSwipes) {
            if (composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()) {
                composeRule.waitForIdle()
                return
            }
            composeRule.onRoot().performTouchInput {
                down(Offset(centerX, height * 0.65f))
                repeat(4) {
                    moveBy(Offset(0f, -height * 0.05f), delayMillis = 30)
                }
                moveBy(Offset(0f, -2f), delayMillis = 100)
                up()
            }
            composeRule.waitForIdle()
        }
        composeRule.waitUntil(30_000) {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun sheetContentScrollsOnDevice() {
        RefreshCounter.count.intValue = 0
        openSheet(buildStore())

        scrollUntilTextVisible("Smoke Row 20")
    }

    @Test
    fun pullToRefreshTriggersOnDevice() {
        RefreshCounter.count.intValue = 0
        openSheet(buildStore())

        composeRule.onRoot().performTouchInput {
            down(Offset(centerX, height * 0.5f))
            repeat(10) {
                moveBy(Offset(0f, height * 0.06f), delayMillis = 20)
            }
            moveBy(Offset(0f, 2f), delayMillis = 100)
            up()
        }
        composeRule.waitUntil(30_000) { RefreshCounter.count.intValue >= 1 }
    }

    @Test
    fun topEdgeZoneDismissesSheetOnDevice() {
        RefreshCounter.count.intValue = 0
        openSheet(buildStore())

        composeRule.onNodeWithText("Smoke Row 0").assertExists()
        val pillBounds = composeRule.onNodeWithTag("reaktiv-dismiss-indicator")
            .fetchSemanticsNode().boundsInRoot
        val pillCenter = Offset(
            (pillBounds.left + pillBounds.right) / 2f,
            (pillBounds.top + pillBounds.bottom) / 2f
        )
        composeRule.onRoot().performTouchInput {
            down(pillCenter)
            repeat(10) {
                moveBy(Offset(0f, height * 0.08f), delayMillis = 20)
            }
            moveBy(Offset(0f, 2f), delayMillis = 100)
            up()
        }
        composeRule.waitUntil(30_000) {
            composeRule.onAllNodesWithText("Smoke Host").fetchSemanticsNodes().isNotEmpty()
        }
    }
}
