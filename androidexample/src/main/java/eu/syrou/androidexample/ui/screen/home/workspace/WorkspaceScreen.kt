package eu.syrou.androidexample.ui.screen.home.workspace

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
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.extension.navigation
import io.github.syrou.reaktiv.navigation.param.Params
import io.github.syrou.reaktiv.navigation.transition.NavTransition
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Serializable
object WorkspaceScreen : Screen {
    override val route = "overview"
    override val enterTransition = NavTransition.None
    override val exitTransition = NavTransition.None
    override val requiresAuth = true

    @Composable
    override fun Content(params: Params) {
        LaunchedEffect(Unit) {
            println("HERPADERPA - WorkspaceScreen Should trigger once")
        }
        WorkspaceContent()
    }
}

@Composable
private fun WorkspaceContent() {
    val scope = rememberCoroutineScope()
    val store = rememberStore()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Workspace Dashboard", style = MaterialTheme.typography.bodyMedium)
        Text("Level 2 - No tabs here")

        Button(onClick = {
            scope.launch {
                store.navigation {
                    navigateTo("home/workspace/projects/overview")
                }
            }
        }) {
            Text("Open Projects (tabs appear)")
        }

        Button(onClick = {
            scope.launch {
                store.navigation {
                    clearBackStack()
                    navigateTo("home")
                }
            }
        }) {
            Text("Back to Home")
        }
    }
}