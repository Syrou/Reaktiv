package eu.syrou.example.ui.screen.home.news

import io.github.syrou.reaktiv.compose.rememberStore
import eu.syrou.example.domain.data.ALL_CATEGORIES
import eu.syrou.example.ui.component.CategoryFilterRow
import eu.syrou.example.reaktiv.news.NewsLogic
import kotlinx.coroutines.launch
import io.github.syrou.reaktiv.navigation.definition.BackstackLifecycle
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.syrou.example.domain.data.NewsItem
import eu.syrou.example.reaktiv.news.NewsModule
import io.github.syrou.reaktiv.compose.composeState
import io.github.syrou.reaktiv.core.StoreAccessor
import io.github.syrou.reaktiv.navigation.transition.NavTransition
import io.github.syrou.reaktiv.navigation.alias.TitleResource
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.param.Params
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import io.github.syrou.reaktiv.navigation.definition.DismissIndicatorPlacement

object NewsListScreen : Screen {
    override suspend fun onLifecycleCreated(lifecycle: BackstackLifecycle) {
        lifecycle.launch { lifecycle.selectLogic<NewsLogic>().load() }
    }


    override val route: String = "list"
    override val titleResource: TitleResource = {
        "News"
    }
    override val enterTransition: NavTransition = NavTransition.SlideUpBottom
    override val exitTransition: NavTransition = NavTransition.SlideOutBottom
    override val dismissIndicatorPlacement: DismissIndicatorPlacement = DismissIndicatorPlacement.Surface

    @Composable
    override fun Content(
        params: Params
    ) {
        NewsListScreen()
    }
}

@Composable
fun NewsListScreen() {
    val uriHandler = LocalUriHandler.current
    val store = rememberStore()
    val newsState by composeState<NewsModule.NewsState>()
    val selectedCategory = newsState.categoryFilter

    Column {
        CategoryFilterRow(
            categories = newsState.news.map { it.source }.distinct(),
            selected = selectedCategory,
            onSelect = { store.dispatch(NewsModule.NewsAction.SetCategoryFilter(it)) }
        )
        LazyColumn {
            items(newsState.news.filter {
                selectedCategory == ALL_CATEGORIES || it.source.contains(selectedCategory)
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
    return "${localDate.month.name.lowercase().replaceFirstChar { it.uppercase() }} ${localDate.day}, ${localDate.year}"
}