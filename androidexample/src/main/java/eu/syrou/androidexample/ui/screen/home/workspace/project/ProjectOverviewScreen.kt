package eu.syrou.androidexample.ui.screen.home.workspace.project

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.syrou.reaktiv.compose.rememberStore
import io.github.syrou.reaktiv.core.StoreAccessor
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.extension.navigateBack
import io.github.syrou.reaktiv.navigation.extension.navigation
import io.github.syrou.reaktiv.navigation.param.Params
import io.github.syrou.reaktiv.navigation.transition.NavTransition
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Serializable
object ProjectOverviewScreen : Screen {
    override val route = "overview"
    override val enterTransition = NavTransition.None
    override val exitTransition = NavTransition.SlideOutLeft
    override val requiresAuth = true

    @Composable
    override fun Content(params: Params) {
        LaunchedEffect(Unit) {
            println("HERPADERPA - ProjectOverviewScreen Should trigger once")
        }
        ProjectOverviewContent(params)
    }
}

@Serializable
object ProjectTasksScreen : Screen {
    override val route = "tasks"
    override val enterTransition = NavTransition.SlideInRight
    override val exitTransition = NavTransition.SlideOutLeft
    override val requiresAuth = true

    @Composable
    override fun Content(params: Params) {
        ProjectTasksContent(params)
    }

    override suspend fun onAddedToBackstack(storeAccessor: StoreAccessor) {
        println("HERPADERPA - ADDED TO BACKSTACK")
    }

    override suspend fun onRemovedFromBackstack(storeAccessor: StoreAccessor) {
        println("HERPADERPA - REMOVED FROM BACKSTACK")
    }
}

@Serializable
object ProjectFilesScreen : Screen {
    override val route = "files"
    override val enterTransition = NavTransition.SlideInRight
    override val exitTransition = NavTransition.SlideOutLeft
    override val requiresAuth = true

    @Composable
    override fun Content(params: Params) {
        ProjectFilesContent(params)
    }
}

@Serializable
object ProjectSettingsScreen : Screen {
    override val route = "settings"
    override val enterTransition = NavTransition.SlideInRight
    override val exitTransition = NavTransition.SlideOutLeft
    override val requiresAuth = true

    @Composable
    override fun Content(params: Params) {
        ProjectSettingsContent(params)
    }
}

@Composable
private fun ProjectOverviewContent(params: Params) {
    val scope = rememberCoroutineScope()
    val projectId = params.getString("projectId") ?: "current"
    val store = rememberStore()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("Project Overview", style = MaterialTheme.typography.bodyMedium)
        Text("Level 3 - Notice the tabs above!")
        Text("Project: $projectId")

        Button(onClick = {
            scope.launch {
                store.navigation {
                    navigateTo("home/workspace/projects/tasks"){
                        putString("projectId", projectId)
                    }
                }
            }
        }) {
            Text("View Tasks")
        }

        Button(onClick = {
            scope.launch {
                store.navigation {
                    navigateTo("home/workspace/overview")
                    popUpTo("home/workspace", inclusive = true)
                }
            }
        }) {
            Text("Back to Workspace")
        }
    }
}

@Composable
private fun ProjectTasksContent(params: Params) {
    val scope = rememberCoroutineScope()
    val projectId = params.getString("projectId") ?: "current"
    val store = rememberStore()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("Project Tasks", style = MaterialTheme.typography.bodyMedium)
        Text("Level 3 - Tabs persist when switching")
        Text("Project: $projectId")

        Button(onClick = {
            scope.launch {
                store.navigation {
                    navigateTo("home/workspace/projects/files")
                }
            }
        }) {
            Text("Switch to Files Tab")
        }

        Button(onClick = {
            scope.launch {
                store.navigateBack()
            }
        }) {
            Text("Back")
        }
    }
}

@Composable
private fun ProjectFilesContent(params: Params) {
    val scope = rememberCoroutineScope()
    val store = rememberStore()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("Project Files", style = MaterialTheme.typography.bodyMedium)
        Text("Level 3 - File management with persistent tabs")

        Button(onClick = {
            scope.launch {
                store.navigation {
                    navigateTo("home/workspace/projects/settings")
                }
            }
        }) {
            Text("Project Settings")
        }
    }
}

@Composable
private fun ProjectSettingsContent(params: Params) {
    val scope = rememberCoroutineScope()
    val store = rememberStore()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("Project Settings", style = MaterialTheme.typography.bodyMedium)
        Text("Level 3 - Configure project with tabs above")

        Button(onClick = {
            scope.launch {
                store.navigation {
                    navigateTo("home/workspace/projects/overview")
                }
            }
        }) {
            Text("Back to Overview")
        }
    }
}