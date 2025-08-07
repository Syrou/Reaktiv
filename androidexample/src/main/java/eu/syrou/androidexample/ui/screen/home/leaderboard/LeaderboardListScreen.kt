package eu.syrou.androidexample.ui.screen.home.leaderboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.syrou.androidexample.domain.model.PlayerProfile
import io.github.syrou.reaktiv.compose.rememberStore
import io.github.syrou.reaktiv.navigation.alias.TitleResource
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.extension.navigate
import io.github.syrou.reaktiv.navigation.extension.navigation
import io.github.syrou.reaktiv.navigation.transition.NavTransition
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Serializable
object LeaderboardListScreen : Screen {
    override val route: String = "overview"
    override val titleResource: TitleResource = { "Leaderboard" }
    override val enterTransition: NavTransition = NavTransition.None
    override val exitTransition: NavTransition = NavTransition.None
    override val requiresAuth: Boolean = false

    @Composable
    override fun Content(params: Map<String, Any>) {
        println("HERPAERPA - LeaderboardListScreen - Content")
        val scope = rememberCoroutineScope()
        val store = rememberStore()

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
            contentPadding = PaddingValues(16.dp)
        ) {
            item {
                Text(
                    "Leaderboard",
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
            }
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clickable {
                            scope.launch {
                                store.navigate("home/leaderboard/detail/weekly")
                            }
                        }
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Weekly Leaderboard", style = MaterialTheme.typography.titleMedium)
                        Text("Top performers this week", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clickable {
                            scope.launch {
                                store.navigate("home/leaderboard/detail/monthly")
                            }
                        }
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Monthly Leaderboard", style = MaterialTheme.typography.titleMedium)
                        Text("Top performers this month", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            items(10) { index ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp)
                        .clickable {
                            scope.launch {
                                store.navigation {
                                    navigateTo("home/leaderboard/player/${index + 1}")
                                    put<PlayerProfile>("profile", createMockPlayerProfile((index + 1).toString(), 99))
                                    putString("source", "leaderboard_list")
                                    putInt("originalRank", index + 1)
                                    putBoolean("fromLeaderboard", true)
                                }
                            }
                        }
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "#${index + 1}",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.width(40.dp)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Player ${index + 1}", style = MaterialTheme.typography.titleSmall)
                            Text("Score: ${1000 - index * 50}", style = MaterialTheme.typography.bodySmall)
                        }
                        Icon(Icons.Default.ArrowForward, contentDescription = "View Profile")
                    }
                }
            }
        }
    }
}