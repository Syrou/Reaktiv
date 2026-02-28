package eu.syrou.androidexample.ui.screen.home.leaderboard

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import eu.syrou.androidexample.domain.model.Achievement
import eu.syrou.androidexample.domain.model.AchievementRarity
import eu.syrou.androidexample.domain.model.CombatStats
import eu.syrou.androidexample.domain.model.ComparisonMetrics
import eu.syrou.androidexample.domain.model.CompetitiveStats
import eu.syrou.androidexample.domain.model.DetailedIndividualStats
import eu.syrou.androidexample.domain.model.EconomyStats
import eu.syrou.androidexample.domain.model.IndividualStatsData
import eu.syrou.androidexample.domain.model.PerformanceMetrics
import eu.syrou.androidexample.domain.model.PlayerComparison
import eu.syrou.androidexample.domain.model.PlayerProfile
import eu.syrou.androidexample.domain.model.PlayerStats
import eu.syrou.androidexample.domain.model.ProgressionData
import eu.syrou.androidexample.domain.model.SeasonStats
import eu.syrou.androidexample.domain.model.SkillProgress
import eu.syrou.androidexample.domain.model.SocialStats
import eu.syrou.androidexample.domain.model.TeamMember
import eu.syrou.androidexample.domain.model.TeamPerformance
import eu.syrou.androidexample.domain.model.TeamStatsData
import io.github.syrou.reaktiv.compose.rememberStore
import io.github.syrou.reaktiv.navigation.alias.TitleResource
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.extension.navigation
import io.github.syrou.reaktiv.navigation.param.Params
import io.github.syrou.reaktiv.navigation.transition.NavTransition
import kotlinx.coroutines.launch

object PlayerProfileScreen : Screen {
    override val route: String = "player/{playerId}"
    override val titleResource: TitleResource = { "Player Profile" }
    override val enterTransition: NavTransition = NavTransition.SlideInRight
    override val exitTransition: NavTransition = NavTransition.SlideOutLeft

    @Composable
    override fun Content(params: Params) {
        val scope = rememberCoroutineScope()
        val store = rememberStore()
        val playerId = params.getString("playerId") ?: "0"
        val playerProfile = params.getTyped<PlayerProfile>("profile")
        val profile = playerProfile ?: createMockPlayerProfile(playerId)

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                PlayerHeaderCard(profile = profile)
            }
            item {
                QuickStatsCard(profile = profile)
            }
            item {
                Text(
                    "Recent Achievements",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }

            items(profile.achievements.take(5)) { achievement ->
                AchievementCard(achievement = achievement)
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            scope.launch {
                                store.navigation {
                                    navigateTo("home/leaderboard/stats/individual") {
                                        put<IndividualStatsData>("playerData", createMockIndividualStats(playerId))
                                        putString("playerId", playerId)
                                    }
                                }
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("View Detailed Stats")
                    }

                    Button(
                        onClick = {
                            scope.launch {
                                store.navigation {
                                    navigateTo("home/leaderboard/stats/team") {
                                        put<TeamStatsData>("teamData", createMockTeamStats(playerId))
                                        putString("playerId", playerId)
                                    }
                                }
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("View Team Stats")
                    }
                }
            }
        }
    }
}

@Composable
private fun PlayerHeaderCard(profile: PlayerProfile) {
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
                text = profile.name,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Rank #${profile.rank}",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = "Level ${profile.level}",
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = "Score: ${profile.score}",
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
private fun QuickStatsCard(profile: PlayerProfile) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                "Quick Stats",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                StatItem("Win Rate", "${String.format("%.1f", profile.winRate * 100)}%")
                StatItem("Games Played", "${profile.gamesPlayed}")
                StatItem("Games Won", "${profile.gamesWon}")
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                StatItem("Total Damage", "${profile.stats.totalDamageDealt}")
                StatItem("Healing Done", "${profile.stats.totalHealingDone}")
                StatItem("Win Streak", "${profile.stats.longestWinStreak}")
            }
        }
    }
}

@Composable
private fun StatItem(label: String, value: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun AchievementCard(achievement: Achievement) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when (achievement.rarity) {
                AchievementRarity.COMMON -> MaterialTheme.colorScheme.surface
                AchievementRarity.UNCOMMON -> MaterialTheme.colorScheme.secondaryContainer
                AchievementRarity.RARE -> MaterialTheme.colorScheme.tertiaryContainer
                AchievementRarity.EPIC -> MaterialTheme.colorScheme.primaryContainer
                AchievementRarity.LEGENDARY -> MaterialTheme.colorScheme.errorContainer
            }
        ),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = achievement.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = achievement.description,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = achievement.rarity.name,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}
fun createMockPlayerProfile(playerId: String, levelOverride: Int? = null): PlayerProfile {
    return PlayerProfile(
        playerId = playerId,
        name = "Player $playerId",
        rank = playerId.toIntOrNull()?.plus(1) ?: 1,
        score = (2000 - (playerId.toIntOrNull() ?: 0) * 75).toLong(),
        level = (levelOverride ?: 45) + (playerId.toIntOrNull() ?: 0),
        winRate = 0.75 - (playerId.toIntOrNull() ?: 0) * 0.01,
        gamesPlayed = 100 + (playerId.toIntOrNull() ?: 0) * 10,
        gamesWon = 75 + (playerId.toIntOrNull() ?: 0) * 7,
        averageGameTime = 1800L,
        achievements = createMockAchievements(),
        stats = createMockPlayerStats(),
        lastActive = kotlin.time.Clock.System.now()
    )
}

private fun createMockPlayerStats() = PlayerStats(
    totalDamageDealt = 150000L,
    totalHealingDone = 45000L,
    highestScore = 2500L,
    longestWinStreak = 12,
    favoriteCharacter = "Warrior",
    totalPlayTime = 360000L,
    rankingHistory = emptyList()
)

private fun createMockAchievements() = listOf(
    Achievement(
        id = "first_win",
        name = "First Victory",
        description = "Win your first match",
        unlockedAt = kotlin.time.Clock.System.now(),
        rarity = AchievementRarity.COMMON
    ),
    Achievement(
        id = "streak_master",
        name = "Streak Master",
        description = "Win 10 games in a row",
        unlockedAt = kotlin.time.Clock.System.now(),
        rarity = AchievementRarity.EPIC
    ),
    Achievement(
        id = "damage_dealer",
        name = "Damage Dealer",
        description = "Deal 100,000 total damage",
        unlockedAt = kotlin.time.Clock.System.now(),
        rarity = AchievementRarity.RARE
    )
)

private fun createMockIndividualStats(playerId: String): IndividualStatsData {
    return IndividualStatsData(
        playerId = playerId,
        playerName = "Player $playerId",
        detailedStats = DetailedIndividualStats(
            combatStats = CombatStats(
                killDeathRatio = 2.5,
                averageDamagePerGame = 1500.0,
                criticalHitRate = 0.25,
                mostUsedWeapon = "Sword",
                totalKills = 500,
                totalDeaths = 200
            ),
            economyStats = EconomyStats(
                totalCurrencyEarned = 50000L,
                totalCurrencySpent = 45000L,
                mostExpensivePurchase = "Legendary Sword",
                tradingProfit = 5000L,
                itemsCollected = 150
            ),
            socialStats = SocialStats(
                friendsCount = 25,
                guildMemberships = listOf("Elite Warriors", "Dragon Slayers"),
                messagesExchanged = 1000,
                helpfulVotes = 75,
                reputationScore = 850
            ),
            performanceMetrics = PerformanceMetrics(
                averageReactionTime = 250.0,
                accuracyPercentage = 0.85,
                consistencyScore = 0.92,
                clutchPlays = 15,
                perfectGames = 8
            )
        ),
        comparison = PlayerComparison(
            vsGlobalAverage = ComparisonMetrics(1.2, 0.15, 1.1, 0.08),
            vsRankAverage = ComparisonMetrics(1.05, 0.05, 1.02, 0.03),
            vsFriends = ComparisonMetrics(1.15, 0.12, 1.08, 0.06)
        ),
        progressionData = ProgressionData(
            currentLevel = 45,
            experiencePoints = 125000L,
            experienceToNextLevel = 15000L,
            skillProgression = mapOf(
                "Combat" to SkillProgress("Combat", 42, 50000L, 0.85),
                "Strategy" to SkillProgress("Strategy", 38, 40000L, 0.75)
            ),
            milestones = emptyList()
        )
    )
}

private fun createMockTeamStats(playerId: String): TeamStatsData {
    return TeamStatsData(
        teamId = "team_elite_$playerId",
        teamName = "Elite Squad",
        members = listOf(
            TeamMember(
                playerId = playerId,
                name = "Player $playerId",
                role = "Captain",
                joinedAt = kotlin.time.Clock.System.now(),
                contributionScore = 0.92,
                isLeader = true,
                isActive = true
            )
        ),
        teamPerformance = TeamPerformance(
            overallRating = 4.2,
            teamworkScore = 0.88,
            coordinationRating = 0.85,
            winStreakRecord = 15,
            averageGameDuration = 1800L,
            strongestGameMode = "Ranked",
            weakestGameMode = "Arena"
        ),
        teamAchievements = emptyList(),
        competitiveStats = CompetitiveStats(
            currentSeason = SeasonStats(
                season = "Season 5",
                rank = 12,
                points = 2500L,
                matchesPlayed = 50,
                matchesWon = 35,
                matchesLost = 15,
                winRate = 0.7
            ),
            previousSeasons = emptyList(),
            tournamentHistory = emptyList(),
            rivalries = emptyList()
        )
    )
}