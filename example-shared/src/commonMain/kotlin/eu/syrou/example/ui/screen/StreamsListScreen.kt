package eu.syrou.example.ui.screen

import io.github.syrou.reaktiv.compose.rememberStore
import eu.syrou.example.domain.data.ALL_CATEGORIES
import eu.syrou.example.ui.component.CategoryFilterRow
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.syrou.example.ui.util.RemoteImage
import eu.syrou.example.ui.util.RemoteImageStyle
import eu.syrou.example.reaktiv.twitchstreams.TwitchStreamsModule
import io.github.syrou.reaktiv.compose.composeState
import io.github.syrou.reaktiv.navigation.transition.NavTransition
import io.github.syrou.reaktiv.navigation.alias.TitleResource
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.param.Params

object StreamsListScreen : Screen {
    override val route: String = "streams-list-screen"
    override val titleResource: TitleResource = {
        "Streams"
    }
    override val enterTransition: NavTransition = NavTransition.SlideInRight
    override val exitTransition: NavTransition = NavTransition.SlideOutLeft

    @Composable
    override fun Content(
        params: Params
    ) {
        StreamsListScreen()
    }
}

@Composable
fun StreamsListScreen() {
    val urlHandler = LocalUriHandler.current
    val store = rememberStore()
    val twitchStreamState by composeState<TwitchStreamsModule.TwitchStreamsState>()
    val streamItems = twitchStreamState.twitchStreamers
    val selectedCategory = twitchStreamState.categoryFilter
    Column(modifier = Modifier.background(MaterialTheme.colorScheme.background)) {
        CategoryFilterRow(
            categories = streamItems.flatMap { it.tags ?: emptyList() }.distinct(),
            selected = selectedCategory,
            onSelect = { store.dispatch(TwitchStreamsModule.TwitchStreamsAction.SetCategoryFilter(it)) }
        )
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(streamItems.filter {
                selectedCategory == ALL_CATEGORIES || it.tags?.contains(selectedCategory) == true
            }) { streamItem ->
                ListItem(
                    headlineContent = {
                        Text(
                            "${streamItem.user_name} - ${streamItem.title}",
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    supportingContent = {
                        Text(
                            streamItem.tags?.joinToString() ?: "",
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    leadingContent = {
                        RemoteImage(
                            url = streamItem.getThumbnailOfSize(320, 240),
                            style = RemoteImageStyle.Avatar,
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                        )
                    },
                    modifier = Modifier.clickable {
                        urlHandler.openUri(streamItem.getTwitchUrl())
                    }
                )
            }
        }
    }
}