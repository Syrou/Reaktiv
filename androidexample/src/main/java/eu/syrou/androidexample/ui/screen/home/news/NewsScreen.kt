package eu.syrou.androidexample.ui.screen.home.news

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.syrou.androidexample.ui.screen.LifecycleDemoScreen
import eu.syrou.androidexample.ui.screen.PullToRefreshDemoScreen
import io.github.syrou.reaktiv.compose.rememberStore
import io.github.syrou.reaktiv.navigation.extension.navigation
import io.github.syrou.reaktiv.navigation.alias.TitleResource
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.param.Params
import io.github.syrou.reaktiv.navigation.transition.NavTransition
import kotlinx.coroutines.launch

object NewsScreen : Screen {
    override val route: String = "overview"
    override val titleResource: TitleResource = {
        "Home"
    }
    override val enterTransition: NavTransition = NavTransition.None
    override val exitTransition: NavTransition = NavTransition.None


    @Composable
    override fun Content(
        params: Params
    ) {
        val store = rememberStore()
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
            contentPadding = PaddingValues(16.dp)
        ) {
            item {
                Text(
                    "Home Screen",
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
            }
            item {
                Button(
                    onClick = {
                        store.launch {
                            store.navigation {
                                navigateTo(PullToRefreshDemoScreen.route)
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Pull To Refresh + Swipe Dismiss Demo")
                }
            }
            item {
                Button(
                    onClick = {
                        store.launch {
                            store.navigation {
                                navigateTo(LifecycleDemoScreen.route)
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Lifecycle Demo (fields clear on exit)")
                }
            }
            item { NewsSection() }
            item { VideosSection() }
            item { LiveStreamsSection() }
        }
    }
}