package eu.syrou.example.ui.screen.home.workspace.project

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Tab
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import io.github.syrou.reaktiv.compose.composeState
import io.github.syrou.reaktiv.compose.rememberStore
import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.extension.navigation
import kotlinx.coroutines.launch

@Composable
fun ProjectTabLayout(content: @Composable () -> Unit) {
    val navigationState by composeState<NavigationState>()
    val store = rememberStore()
    val activeTab = PROJECT_TABS.indexOfFirst { navigationState.isAtPath(it.path) }.coerceAtLeast(0)

    Column(modifier = Modifier.fillMaxSize()) {
        PrimaryTabRow(
            selectedTabIndex = activeTab,
            modifier = Modifier
                .fillMaxWidth()
        ) {
            PROJECT_TABS.forEachIndexed { index, tab ->
                Tab(
                    selected = activeTab == index,
                    onClick = {
                        store.launch {
                            store.navigation {
                                navigateTo("home/workspace/projects/${tab.path}")
                            }
                        }
                    },
                    text = { Text(tab.label) }
                )
            }
        }
        content()
    }
}

private class ProjectTab(val path: String, val label: String)

private val PROJECT_TABS = listOf(
    ProjectTab("overview", "Overview"),
    ProjectTab("tasks", "Tasks"),
    ProjectTab("files", "Files"),
    ProjectTab("settings", "Settings")
)
