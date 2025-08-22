package eu.syrou.androidexample.ui.screen.home.leaderboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.syrou.androidexample.ui.screen.TwitchAuthWebViewScreen
import eu.syrou.androidexample.ui.screen.VideosListScreen
import io.github.syrou.reaktiv.compose.rememberStore
import io.github.syrou.reaktiv.navigation.alias.TitleResource
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.param.Params
import io.github.syrou.reaktiv.navigation.extension.navigation
import io.github.syrou.reaktiv.navigation.transition.NavTransition
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Serializable
object LeaderboardDetailScreen : Screen {
    override val route: String = "detail/{period}"
    override val titleResource: TitleResource = { "Leaderboard Details" }
    override val enterTransition: NavTransition = NavTransition.SlideInRight
    override val exitTransition: NavTransition = NavTransition.SlideOutLeft
    override val requiresAuth: Boolean = false

    @Composable
    override fun Content(params: Params) {
        val scope = rememberCoroutineScope()
        val store = rememberStore()
        val period = params.getString("period") ?: "weekly"

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
            contentPadding = PaddingValues(16.dp)
        ) {
            item {
                Text(
                    "${period.capitalize()} Leaderboard",
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
            }

            item {
                Button(onClick = {
                    scope.launch {
                        store.navigation {
                            navigateTo<VideosListScreen>()
                        }
                    }
                }) {
                    Text("Press")
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    Button(
                        onClick = {
                            scope.launch {
                                store.navigation {
                                    navigateTo("home/leaderboard/stats/team")
                                }
                            }
                        }
                    ) {
                        Text("Team Stats")
                    }

                    Button(
                        onClick = {
                            scope.launch {
                                store.navigation {
                                    navigateTo("home/leaderboard/stats/individual")
                                }
                            }
                        }
                    ) {
                        Text("Individual Stats")
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
            items(20) { index ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp)
                        .clickable {
                            scope.launch {
                                store.navigation {
                                    navigateTo("home/leaderboard/player/${index + 1}")
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
                            modifier = Modifier.width(50.dp)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Player ${index + 1}", style = MaterialTheme.typography.titleSmall)
                            Text("Score: ${2000 - index * 75}", style = MaterialTheme.typography.bodySmall)
                            Text("Games: ${50 - index}", style = MaterialTheme.typography.bodySmall)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("${(95 - index)}%", style = MaterialTheme.typography.titleSmall)
                            Text("Win Rate", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}