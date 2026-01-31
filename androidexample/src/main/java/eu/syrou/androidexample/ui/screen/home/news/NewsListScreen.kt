package eu.syrou.androidexample.ui.screen.home.news

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.syrou.androidexample.domain.data.NewsItem
import eu.syrou.androidexample.reaktiv.news.NewsModule
import io.github.syrou.reaktiv.compose.composeState
import io.github.syrou.reaktiv.core.StoreAccessor
import io.github.syrou.reaktiv.navigation.transition.NavTransition
import io.github.syrou.reaktiv.navigation.alias.TitleResource
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.param.Params
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import java.util.*

@Serializable
object NewsListScreen : Screen {

    override val route: String = "list"
    override val titleResource: TitleResource = {
        "News"
    }
    override val enterTransition: NavTransition = NavTransition.SlideUpBottom
    override val exitTransition: NavTransition = NavTransition.SlideOutBottom
    override val requiresAuth: Boolean = false

    @Composable
    override fun Content(
        params: Params
    ) {
        LaunchedEffect(Unit) {
            println("HERPADERPA - HMM")
        }
        DisposableEffect(Unit) {
            onDispose {
                println("HERPADERPA - DISPOSED")
            }
        }
        NewsListScreen()
    }
}

@Composable
fun NewsListScreen() {
    val uriHandler = LocalUriHandler.current
    val newsState by composeState<NewsModule.NewsState>()
    var selectedCategory by remember { mutableStateOf("All") }
    val categories = newsState.news.map { it.source }.distinct().toMutableList().also {
        it.add(0, "All")
    }

    Column {
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
        LazyColumn {
            items(newsState.news.filter {
                selectedCategory == "All" || it.source.contains(selectedCategory)
            }) { newsItem ->
                NewsCard(newsItem) {
                    uriHandler.openUri(it)
                }
            }
        }
    }
}

@Composable
fun NewsCard(newsItem: NewsItem, onNewsItemClick: (String) -> Unit) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clickable { onNewsItemClick(newsItem.link) },
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                AssistChip(
                    onClick = { /* Handle source click if needed */ },
                    label = { Text(newsItem.source) },
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        labelColor = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                )
                Text(
                    text = formatDate(newsItem.pubDate),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = newsItem.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = newsItem.description ?: "Click to read more...",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

fun formatDate(instant: kotlin.time.Instant): String {
    val localDate = instant.toLocalDateTime(TimeZone.currentSystemDefault()).date
    return "${localDate.month.name.lowercase().capitalize(Locale.ROOT)} ${localDate.dayOfMonth}, ${localDate.year}"
}