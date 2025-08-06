package eu.syrou.androidexample.ui.screen.home.workspace.project

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import io.github.syrou.reaktiv.compose.composeState
import io.github.syrou.reaktiv.compose.rememberStore
import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.extension.navigate
import kotlinx.coroutines.launch

@Composable
fun ProjectTabLayout(content: @Composable () -> Unit) {
    val scope = rememberCoroutineScope()
    val navigationState by composeState<NavigationState>()
    val store = rememberStore()
    val activeTab = when {
        navigationState.isAtPath("overview") -> 0
        navigationState.isAtPath("tasks") -> 1
        navigationState.isAtPath("files") -> 2
        navigationState.isAtPath("settings") -> 3
        else -> 0
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TabRow(
            selectedTabIndex = activeTab,
            modifier = Modifier
                .fillMaxWidth()
        ) {
            Tab(
                selected = activeTab == 0,
                onClick = {
                    scope.launch {
                        store.navigate("home/workspace/projects/overview")
                    }
                },
                text = { Text("Overview") }
            )
            Tab(
                selected = activeTab == 1,
                onClick = {
                    scope.launch {
                        store.navigate("home/workspace/projects/tasks")
                    }
                },
                text = { Text("Tasks") }
            )
            Tab(
                selected = activeTab == 2,
                onClick = {
                    scope.launch {
                        store.navigate("home/workspace/projects/files")
                    }
                },
                text = { Text("Files") }
            )
            Tab(
                selected = activeTab == 3,
                onClick = {
                    scope.launch {
                        store.navigate("home/workspace/projects/settings")
                    }
                },
                text = { Text("Settings") }
            )
        }
        content()
    }
}