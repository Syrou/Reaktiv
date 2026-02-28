package eu.syrou.androidexample.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.transform.CircleCropTransformation
import eu.syrou.androidexample.reaktiv.twitchstreams.TwitchStreamsModule
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
    val twitchStreamState by composeState<TwitchStreamsModule.TwitchStreamsState>()
    val streamItems = twitchStreamState.twitchStreamers
    var selectedCategory by remember { mutableStateOf("All") }
    val categories = streamItems.flatMap { it.tags ?: emptyList() }.distinct().toMutableList().also {
        it.add(0, "All")
    }
    Column(modifier = Modifier.background(MaterialTheme.colorScheme.background)) {
        LazyRow(modifier = Modifier.padding(vertical = 8.dp)) {
            items(categories) { category ->
                FilterChip(
                    selected = category == selectedCategory,
                    onClick = { selectedCategory = category },
                    label = { Text(category) },
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(streamItems.filter {
                selectedCategory == "All" || it.tags?.contains(selectedCategory) == true
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
                        AsyncImage(
                            model = ImageRequest.Builder(LocalContext.current)
                                .data(streamItem.getThumbnailOfSize(320, 240))
                                .transformations(CircleCropTransformation())
                                .crossfade(true)
                                .build(), contentDescription = "",
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