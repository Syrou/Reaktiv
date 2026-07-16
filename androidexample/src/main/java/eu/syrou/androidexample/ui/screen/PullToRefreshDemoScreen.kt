package eu.syrou.androidexample.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.syrou.reaktiv.navigation.alias.TitleResource
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.param.Params
import io.github.syrou.reaktiv.navigation.transition.NavTransition
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

object PullToRefreshDemoScreen : Screen {
    override val route: String = "pull-to-refresh-demo"
    override val titleResource: TitleResource = {
        "Pull To Refresh Demo"
    }
    override val enterTransition: NavTransition = NavTransition.SlideUpBottom
    override val exitTransition: NavTransition = NavTransition.SlideOutBottom

    @Composable
    override fun Content(
        params: Params
    ) {
        PullToRefreshDemoContent()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PullToRefreshDemoContent() {
    val scope = rememberCoroutineScope()
    var isRefreshing by remember { mutableStateOf(false) }
    var refreshCount by remember { mutableIntStateOf(0) }
    var listItems by remember { mutableStateOf((1..30).map { "Row $it" }) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = {
                scope.launch {
                    isRefreshing = true
                    delay(3000)
                    refreshCount++
                    listItems = listOf("Refreshed #$refreshCount") + listItems
                    isRefreshing = false
                }
            },
            modifier = Modifier.fillMaxSize()
        ) {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer
                        )
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = "Try these gestures",
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                text = "1. Pull down on this list to refresh",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                text = "2. Drag down from the very top edge of the screen to dismiss, works over any content, no header needed",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                text = "3. Dragging the header also dismisses, chrome outside the refresh box always does",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                text = "4. While the 3s refresh spinner runs, pulling the list dismisses too, like a native sheet",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
                items(listItems) { rowText ->
                    ListItem(headlineContent = { Text(rowText) })
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                }
            }
        }
    }
}
