package eu.syrou.androidexample.ui.screen.home.leaderboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import io.github.syrou.reaktiv.compose.rememberStore
import io.github.syrou.reaktiv.navigation.alias.TitleResource
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.extension.navigate
import io.github.syrou.reaktiv.navigation.transition.NavTransition
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Serializable
object PlayerProfileScreen : Screen {
    override val route: String = "player/{playerId}"
    override val titleResource: TitleResource = { "Player Profile" }
    override val enterTransition: NavTransition = NavTransition.SlideInRight
    override val exitTransition: NavTransition = NavTransition.SlideOutLeft
    override val requiresAuth: Boolean = false

    @Composable
    override fun Content(params: Map<String, Any>) {
        val scope = rememberCoroutineScope()
        val store = rememberStore()
        val playerId = params["playerId"] as? String ?: "0"

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
            contentPadding = PaddingValues(16.dp)
        ) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "Player $playerId",
                            style = MaterialTheme.typography.headlineMedium
                        )
                        Text(
                            "Rank: #${playerId.toIntOrNull()?.plus(1) ?: 1}",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            "Score: ${2000 - (playerId.toIntOrNull() ?: 0) * 75}",
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            item {
                Text("Recent Games", style = MaterialTheme.typography.titleLarge)
                Spacer(modifier = Modifier.height(8.dp))
            }

            items(5) { gameIndex ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Game ${gameIndex + 1}", style = MaterialTheme.typography.titleSmall)
                            Text("Score: ${100 + gameIndex * 20}", style = MaterialTheme.typography.bodySmall)
                        }
                        Text(
                            if (gameIndex % 2 == 0) "WIN" else "LOSS",
                            style = MaterialTheme.typography.titleSmall,
                            color = if (gameIndex % 2 == 0)
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = {
                        scope.launch {
                            store.navigate("home/tab_leaderboard/team/${playerId}")
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("View Team Details")
                }
            }
        }
    }
}