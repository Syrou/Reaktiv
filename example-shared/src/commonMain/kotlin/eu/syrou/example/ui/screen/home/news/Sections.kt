package eu.syrou.example.ui.screen.home.news

import PoegoIcons
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.syrou.example.ui.util.RemoteImage
import eu.syrou.example.ui.util.RemoteImageStyle
import eu.syrou.example.reaktiv.news.NewsModule
import eu.syrou.example.reaktiv.settings.SettingsModule
import eu.syrou.example.reaktiv.twitchstreams.TwitchStreamsModule
import eu.syrou.example.reaktiv.videos.VideosModule
import eu.syrou.example.ui.screen.SettingsScreen
import eu.syrou.example.ui.screen.StreamsListScreen
import io.github.syrou.reaktiv.compose.composeState
import io.github.syrou.reaktiv.compose.rememberStore
import io.github.syrou.reaktiv.navigation.extension.navigation
import kotlinx.coroutines.launch
import eu.syrou.example.LocalExamplePlatform

@Composable
fun SectionHeader(title: String, icon: ImageVector) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(bottom = 8.dp)
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            title,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
    }
}

@Composable
fun NewsSection() {
    val store = rememberStore()
    val urlHandler = LocalUriHandler.current
    val newsState by composeState<NewsModule.NewsState>()
    val latestNews = newsState.news.firstOrNull()
    Column {
        SectionHeader(title = "Latest PoE News", icon = PoegoIcons.News)
        ElevatedCard(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    latestNews?.link?.takeIf { it.isNotEmpty() }?.let {
                        urlHandler.openUri(it)
                    }
                },
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(latestNews?.title ?: "", fontWeight = FontWeight.Medium)
                Text(
                    latestNews?.description ?: "Click for more...",
                    color = Color.LightGray,
                    fontSize = 14.sp
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Button(
            onClick = {
                store.launch {
                    store.navigation {
                        navigateTo<NewsListScreen>()
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
        ) {
            Text("View All News")
            Icon(PoegoIcons.Menu, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary)
        }
    }
}

@Composable
fun VideosSection() {
    val videosState by composeState<VideosModule.VideosState>()
    val uriHandler = LocalUriHandler.current
    Column {
        SectionHeader(title = "PoE Videos", icon = Icons.Default.PlayArrow)

        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(videosState.videos) { item ->
                ElevatedCard(
                    modifier = Modifier
                        .weight(1f)
                        .width(100.dp)
                        .clickable {
                            uriHandler.openUri(item.link)
                        },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(modifier = Modifier.height(150.dp)) {
                        Box(
                            modifier = Modifier
                                .height(55.dp)
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            RemoteImage(
                                url = item.thumbnailUrl,
                                style = RemoteImageStyle.Cover,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                        Text(
                            item.title,
                            modifier = Modifier.padding(8.dp),
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun LiveStreamsSection() {
    val store = rememberStore()
    val twitchStreams by composeState<TwitchStreamsModule.TwitchStreamsState>()
    val settingsState by composeState<SettingsModule.SettingsState>()
    val hasAccess = settingsState.twitchAccessToken?.isNotEmpty() == true
    val urlHandler = LocalUriHandler.current
    Box {
        Column {
            SectionHeader(title = "Live Twitch Streams", icon = PoegoIcons.LiveTv)
            Button(
                onClick = {
                    store.launch {
                        store.navigation {
                            navigateTo(StreamsListScreen.route)
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Text("View All Streams")
                Icon(PoegoIcons.Menu, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary)
            }
            Spacer(modifier = Modifier.height(8.dp))
            ElevatedCard(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            ) {
                Column(modifier = Modifier.takeIf { !hasAccess }?.then(Modifier.blur(radius = 16.dp)) ?: Modifier) {
                    repeat(3) { index ->
                        val streamer = twitchStreams.twitchStreamers.getOrNull(index)
                        ListItem(
                            modifier = Modifier.clickable {
                                streamer?.getTwitchUrl()?.let { urlHandler.openUri(it) }
                            },
                            headlineContent = { Text("Streamer ${streamer?.user_name}") },
                            supportingContent = { Text("Streaming content ${streamer?.game_name}") },
                            leadingContent = {
                                RemoteImage(
                                    url = streamer?.getThumbnailOfSize(320, 240),
                                    style = RemoteImageStyle.Avatar,
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(CircleShape)
                                )
                            },
                            trailingContent = {
                                Surface(
                                    color = MaterialTheme.colorScheme.primary,
                                    shape = CircleShape,
                                    modifier = Modifier.padding(4.dp)
                                ) {
                                    Text(
                                        "LIVE",
                                        color = MaterialTheme.colorScheme.onPrimary,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                        fontSize = 12.sp
                                    )
                                }
                            }
                        )
                        if (index < 2) HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                    }
                }
            }
        }
        if (!hasAccess && LocalExamplePlatform.current.twitchLogin == null) {
            Text(
                text = "Twitch sign in is available in the Android app",
                modifier = Modifier.align(Alignment.Center),
                textAlign = TextAlign.Center
            )
        } else if (!hasAccess) {
            Button(modifier = Modifier.align(Alignment.Center), onClick = {
                store.launch {
                    store.navigation {
                        navigateTo(SettingsScreen.route)
                    }
                }
            }
            ) {
                Text(text = "Authorize twitch api", textAlign = TextAlign.Center)
            }
        }
    }
}

