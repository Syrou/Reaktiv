package eu.syrou.androidexample.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.syrou.androidexample.ui.screen.home.news.NewsScreen
import io.github.syrou.reaktiv.compose.rememberStore
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.extension.navigation
import io.github.syrou.reaktiv.navigation.param.Params
import io.github.syrou.reaktiv.navigation.transition.NavTransition
import kotlinx.coroutines.launch

object NotFoundScreen : Screen {
    override val route: String = "not-found"
    override val enterTransition: NavTransition = NavTransition.None
    override val exitTransition: NavTransition = NavTransition.None
    override val requiresAuth: Boolean = false

    @Composable
    override fun Content(
        params: Params
    ) {
        val store = rememberStore()
        val scope = rememberCoroutineScope()
        Column(
            modifier = Modifier
                .fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                        onClick = {}
                    ),
                contentAlignment = Alignment.Center
            ) {
                Column(modifier = Modifier.width(200.dp)) {
                    Text(
                        text = "Path not bound to any screen",
                    )
                    Button(modifier = Modifier.fillMaxWidth(), onClick = {
                        scope.launch {
                            store.navigation {
                                navigateTo<NewsScreen>(replaceCurrent = true)
                            }
                        }
                    }) {
                        Text("Go home")
                    }
                }
            }
        }
    }
}