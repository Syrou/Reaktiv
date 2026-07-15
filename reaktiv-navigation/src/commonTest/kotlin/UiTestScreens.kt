import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.syrou.reaktiv.navigation.NavigationModule
import io.github.syrou.reaktiv.navigation.createNavigationModule
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.param.Params
import io.github.syrou.reaktiv.navigation.transition.NavTransition

object UiHomeScreen : Screen {
    override val route = "ui-home"
    override val enterTransition = NavTransition.IOSSlideIn
    override val exitTransition = NavTransition.IOSSlideOut

    @Composable
    override fun Content(params: Params) {
        Box(modifier = Modifier.fillMaxSize().testTag("ui-home-screen")) {
            Text("UI Home")
        }
    }
}

object UiDetailScreen : Screen {
    override val route = "ui-detail"
    override val enterTransition = NavTransition.IOSSlideIn
    override val exitTransition = NavTransition.IOSSlideOut

    @Composable
    override fun Content(params: Params) {
        var count by remember { mutableIntStateOf(0) }
        Box(modifier = Modifier.fillMaxSize().testTag("ui-detail-screen")) {
            Column {
                Text("UI Detail")
                Text(
                    text = "Count: $count",
                    modifier = Modifier
                        .testTag("ui-detail-counter")
                        .clickable { count++ }
                )
            }
        }
    }
}

object UiPlainScreen : Screen {
    override val route = "ui-plain"
    override val enterTransition = NavTransition.None
    override val exitTransition = NavTransition.None

    @Composable
    override fun Content(params: Params) {
        Box(modifier = Modifier.fillMaxSize().testTag("ui-plain-screen")) {
            Text("UI Plain")
        }
    }
}

object UiLockedScreen : Screen {
    override val route = "ui-locked"
    override val enterTransition = NavTransition.IOSSlideIn
    override val exitTransition = NavTransition.IOSSlideOut
    override val backGestureEnabled = false

    @Composable
    override fun Content(params: Params) {
        Box(modifier = Modifier.fillMaxSize().testTag("ui-locked-screen")) {
            Text("UI Locked")
        }
    }
}

object UiSheetScreen : Screen {
    override val route = "ui-sheet"
    override val enterTransition = NavTransition.SlideUpBottom
    override val exitTransition = NavTransition.SlideOutBottom

    @Composable
    override fun Content(params: Params) {
        Box(modifier = Modifier.fillMaxSize().testTag("ui-sheet-screen")) {
            Text("UI Sheet")
        }
    }
}

object UiScrollableSheetScreen : Screen {
    override val route = "ui-scroll-sheet"
    override val enterTransition = NavTransition.SlideUpBottom
    override val exitTransition = NavTransition.SlideOutBottom

    @Composable
    override fun Content(params: Params) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .testTag("ui-scroll-sheet-list")
                .verticalScroll(rememberScrollState())
        ) {
            repeat(40) { index ->
                Text(
                    text = "Sheet Row $index",
                    modifier = Modifier.height(48.dp)
                )
            }
        }
    }
}

object UiHorizontalScrollScreen : Screen {
    override val route = "ui-hscroll"
    override val enterTransition = NavTransition.IOSSlideIn
    override val exitTransition = NavTransition.IOSSlideOut

    @Composable
    override fun Content(params: Params) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .testTag("ui-hscroll-row")
                .horizontalScroll(rememberScrollState())
        ) {
            repeat(30) { index ->
                Text(
                    text = "Col $index",
                    modifier = Modifier.width(120.dp)
                )
            }
        }
    }
}

object UiWorkspaceScreen : Screen {
    override val route = "ui-workspace"
    override val enterTransition = NavTransition.IOSSlideIn
    override val exitTransition = NavTransition.IOSSlideOut

    @Composable
    override fun Content(params: Params) {
        Box(modifier = Modifier.fillMaxSize().testTag("ui-workspace-screen")) {
            Text("UI Workspace")
        }
    }
}

object UiProjectScreen : Screen {
    override val route = "ui-project"
    override val enterTransition = NavTransition.IOSSlideIn
    override val exitTransition = NavTransition.IOSSlideOut

    @Composable
    override fun Content(params: Params) {
        Box(modifier = Modifier.fillMaxSize().testTag("ui-project-screen")) {
            Text("UI Project")
        }
    }
}

fun createUiTestModule(): NavigationModule = createNavigationModule {
    rootGraph {
        startScreen(UiHomeScreen)
        screens(
            UiHomeScreen,
            UiDetailScreen,
            UiPlainScreen,
            UiLockedScreen,
            UiSheetScreen,
            UiScrollableSheetScreen,
            UiHorizontalScrollScreen
        )
    }
}

fun createUiGraphTestModule(): NavigationModule = createNavigationModule {
    rootGraph {
        startScreen(UiWorkspaceScreen)
        screens(UiWorkspaceScreen)

        graph("project-area") {
            layout { content ->
                Box(modifier = Modifier.fillMaxSize().testTag("project-chrome")) {
                    content()
                }
            }
            startScreen(UiProjectScreen)
            screens(UiProjectScreen)
        }
    }
}
