package eu.syrou.androidexample.ui.screen.home.leaderboard.compose.individual

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import eu.syrou.androidexample.domain.model.CombatStats
import eu.syrou.androidexample.domain.model.ComparisonMetrics
import eu.syrou.androidexample.domain.model.EconomyStats
import eu.syrou.androidexample.domain.model.IndividualStatsData
import eu.syrou.androidexample.domain.model.PerformanceMetrics
import eu.syrou.androidexample.domain.model.SkillProgress
import eu.syrou.androidexample.domain.model.SocialStats

@Composable
fun PlayerStatsHeader(playerData: IndividualStatsData) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "${playerData.playerName}'s Stats",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Level ${playerData.progressionData.currentLevel}",
                style = MaterialTheme.typography.titleLarge
            )

            Spacer(modifier = Modifier.height(8.dp))
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Experience")
                    Text("${playerData.progressionData.experiencePoints} XP")
                }

                val progress = playerData.progressionData.experiencePoints.toFloat() /
                        (playerData.progressionData.experiencePoints + playerData.progressionData.experienceToNextLevel)

                LinearProgressIndicator(
                    progress = progress,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp),
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
fun CombatStatsCard(combatStats: CombatStats) {
    StatCard(title = "Combat Statistics") {
        StatsGrid(
            stats = listOf(
                StatPair("K/D Ratio", String.format("%.2f", combatStats.killDeathRatio)),
                StatPair("Avg Damage/Game", String.format("%.0f", combatStats.averageDamagePerGame)),
                StatPair("Crit Hit Rate", "${String.format("%.1f", combatStats.criticalHitRate * 100)}%"),
                StatPair("Most Used Weapon", combatStats.mostUsedWeapon),
                StatPair("Total Kills", "${combatStats.totalKills}"),
                StatPair("Total Deaths", "${combatStats.totalDeaths}")
            )
        )
    }
}

@Composable
fun PerformanceMetricsCard(performanceMetrics: PerformanceMetrics) {
    StatCard(title = "Performance Metrics") {
        StatsGrid(
            stats = listOf(
                StatPair("Avg Reaction Time", "${String.format("%.0f", performanceMetrics.averageReactionTime)}ms"),
                StatPair("Accuracy", "${String.format("%.1f", performanceMetrics.accuracyPercentage * 100)}%"),
                StatPair("Consistency Score", "${String.format("%.1f", performanceMetrics.consistencyScore * 100)}%"),
                StatPair("Clutch Plays", "${performanceMetrics.clutchPlays}"),
                StatPair("Perfect Games", "${performanceMetrics.perfectGames}"),
                // Empty for grid alignment
                StatPair("", "")
            )
        )
    }
}

@Composable
fun EconomyStatsCard(economyStats: EconomyStats) {
    StatCard(title = "Economy Statistics") {
        StatsGrid(
            stats = listOf(
                StatPair("Currency Earned", "${economyStats.totalCurrencyEarned}"),
                StatPair("Currency Spent", "${economyStats.totalCurrencySpent}"),
                StatPair("Trading Profit", "${economyStats.tradingProfit}"),
                StatPair("Items Collected", "${economyStats.itemsCollected}"),
                StatPair("Most Expensive", economyStats.mostExpensivePurchase),
                StatPair("Net Worth", "${economyStats.totalCurrencyEarned - economyStats.totalCurrencySpent}")
            )
        )
    }
}

@Composable
fun SocialStatsCard(socialStats: SocialStats) {
    StatCard(title = "Social Statistics") {
        Column {
            StatsGrid(
                stats = listOf(
                    StatPair("Friends", "${socialStats.friendsCount}"),
                    StatPair("Reputation", "${socialStats.reputationScore}"),
                    StatPair("Messages", "${socialStats.messagesExchanged}"),
                    StatPair("Helpful Votes", "${socialStats.helpfulVotes}")
                )
            )

            if (socialStats.guildMemberships.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    "Guild Memberships:",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                socialStats.guildMemberships.forEach { guild ->
                    Text(
                        "• $guild",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun SkillProgressionCard(skillProgression: Map<String, SkillProgress>) {
    StatCard(title = "Skill Progression") {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            skillProgression.values.forEach { skill ->
                SkillProgressItem(skill)
            }
        }
    }
}

@Composable
fun SkillProgressItem(skill: SkillProgress) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                skill.skillName,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                "Level ${skill.currentLevel}",
                style = MaterialTheme.typography.bodyMedium
            )
        }

        LinearProgressIndicator(
            progress = skill.masteryPercentage.toFloat(),
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp),
            color = MaterialTheme.colorScheme.secondary
        )

        Text(
            "Mastery: ${String.format("%.1f", skill.masteryPercentage * 100)}%",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun ComparisonSection(playerData: IndividualStatsData) {
    StatCard(title = "Performance Comparison") {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            ComparisonItem("vs Global Average", playerData.comparison.vsGlobalAverage)
            ComparisonItem("vs Rank Average", playerData.comparison.vsRankAverage)
            ComparisonItem("vs Friends", playerData.comparison.vsFriends)
        }
    }
}

@Composable
fun ComparisonItem(title: String, metrics: ComparisonMetrics) {
    Column {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            ComparisonMetric("Score", metrics.scoreMultiplier, "x")
            ComparisonMetric("Win Rate", metrics.winRateComparison, "%", isPercentage = true)
            ComparisonMetric("Skill", metrics.skillRating, "x")
            ComparisonMetric("Improvement", metrics.improvementRate, "%", isPercentage = true)
        }
    }
}

@Composable
fun ComparisonMetric(
    label: String,
    value: Double,
    suffix: String,
    isPercentage: Boolean = false
) {
    val displayValue = if (isPercentage) {
        String.format("%.1f", value * 100)
    } else {
        String.format("%.2f", value)
    }

    val color = when {
        value > 1.0 || (isPercentage && value > 0) -> Color.Green
        value < 1.0 || (isPercentage && value < 0) -> Color.Red
        else -> MaterialTheme.colorScheme.onSurface
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            "$displayValue$suffix",
            style = MaterialTheme.typography.titleSmall,
            color = color,
            fontWeight = FontWeight.Bold
        )
        Text(
            label,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
fun StatCard(
    title: String,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 12.dp)
            )
            content()
        }
    }
}

@Composable
fun StatsGrid(stats: List<StatPair>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        stats.chunked(2).forEach { rowStats ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                rowStats.forEach { stat ->
                    if (stat.label.isNotEmpty()) {
                        StatItem(
                            label = stat.label,
                            value = stat.value,
                            modifier = Modifier.weight(1f)
                        )
                    } else {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
fun StatItem(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.Start
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

data class StatPair(val label: String, val value: String)