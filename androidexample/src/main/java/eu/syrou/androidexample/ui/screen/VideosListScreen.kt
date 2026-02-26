package eu.syrou.androidexample.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import eu.syrou.androidexample.reaktiv.videos.VideosModule
import io.github.syrou.reaktiv.compose.composeState
import io.github.syrou.reaktiv.navigation.transition.NavTransition
import io.github.syrou.reaktiv.navigation.alias.TitleResource
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.param.Params

object VideosListScreen : Screen {
    override val route: String = "videos-list-screen"
    override val titleResource: TitleResource = {
        "Videos"
    }
    override val enterTransition: NavTransition = NavTransition.SlideUpBottom
    override val exitTransition: NavTransition = NavTransition.SlideOutBottom
    override val popEnterTransition: NavTransition = NavTransition.None
    override val popExitTransition: NavTransition = NavTransition.None
    override val requiresAuth: Boolean = false

    @Composable
    override fun Content(
        params: Params
    ) {
        VideosListScreen()
    }
}

@Composable
fun VideosListScreen() {
    val videos by composeState<VideosModule.VideosState>()

    Column {
        LazyColumn {
            items(videos.videos) { newsItem ->
                ListItem(
                    headlineContent = { Text(newsItem.title) },
                    modifier = Modifier.clickable { }
                )
            }
        }
    }
}