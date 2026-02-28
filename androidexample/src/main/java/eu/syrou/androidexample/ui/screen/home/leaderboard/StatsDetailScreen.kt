package eu.syrou.androidexample.ui.screen.home.leaderboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import eu.syrou.androidexample.domain.model.IndividualStatsData
import eu.syrou.androidexample.domain.model.TeamStatsData
import eu.syrou.androidexample.ui.screen.home.leaderboard.compose.individual.CombatStatsCard
import eu.syrou.androidexample.ui.screen.home.leaderboard.compose.individual.ComparisonSection
import eu.syrou.androidexample.ui.screen.home.leaderboard.compose.individual.EconomyStatsCard
import eu.syrou.androidexample.ui.screen.home.leaderboard.compose.individual.PerformanceMetricsCard
import eu.syrou.androidexample.ui.screen.home.leaderboard.compose.individual.PlayerStatsHeader
import eu.syrou.androidexample.ui.screen.home.leaderboard.compose.individual.SkillProgressionCard
import eu.syrou.androidexample.ui.screen.home.leaderboard.compose.individual.SocialStatsCard
import eu.syrou.androidexample.ui.screen.home.leaderboard.compose.team.CurrentSeasonCard
import eu.syrou.androidexample.ui.screen.home.leaderboard.compose.team.RivalriesCard
import eu.syrou.androidexample.ui.screen.home.leaderboard.compose.team.TeamAchievementCard
import eu.syrou.androidexample.ui.screen.home.leaderboard.compose.team.TeamHeader
import eu.syrou.androidexample.ui.screen.home.leaderboard.compose.team.TeamMembersCard
import eu.syrou.androidexample.ui.screen.home.leaderboard.compose.team.TeamPerformanceCard
import eu.syrou.androidexample.ui.screen.home.leaderboard.compose.team.TournamentHistoryCard
import io.github.syrou.reaktiv.navigation.alias.TitleResource
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.param.Params
import io.github.syrou.reaktiv.navigation.transition.NavTransition

object StatsDetailScreen : Screen {
    override val route: String = "stats/{type}"
    override val titleResource: TitleResource = { "Statistics" }
    override val enterTransition: NavTransition = NavTransition.SlideInRight
    override val exitTransition: NavTransition = NavTransition.SlideOutLeft

    @Composable
    override fun Content(params: Params) {
        val type = params.getString("type") ?: "general"
        val teamData = params.getTyped<TeamStatsData>("teamData")
        val playerData = params.getTyped<IndividualStatsData>("playerData")
        val playerId = params.getString("playerId") ?: "unknown"
        if (teamData == null && playerData == null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    "No player data available",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    "Player ID: $playerId",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        if (teamData != null || playerData != null) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item {
                    when (type) {
                        "team" -> TeamHeader(teamData!!)
                        "individual" -> PlayerStatsHeader(playerData!!)
                    }
                }
                item {
                    when (type) {
                        "team" -> TeamPerformanceCard(teamData!!.teamPerformance)
                        "individual" -> CombatStatsCard(playerData!!.detailedStats.combatStats)
                    }
                }
                item {
                    when (type) {
                        "team" -> TeamPerformanceCard(teamData!!.teamPerformance)
                        "individual" -> PerformanceMetricsCard(playerData!!.detailedStats.performanceMetrics)
                    }
                }
                item {
                    when (type) {
                        "team" -> CurrentSeasonCard(teamData!!.competitiveStats.currentSeason)
                        "individual" -> EconomyStatsCard(playerData!!.detailedStats.economyStats)
                    }

                }
                item {
                    when (type) {
                        "team" -> TeamMembersCard(teamData!!.members)
                        "individual" -> SocialStatsCard(playerData!!.detailedStats.socialStats)
                    }

                }
                when (type) {
                    "individual" -> item {
                        SkillProgressionCard(playerData!!.progressionData.skillProgression)
                    }

                    "team" -> if (teamData!!.teamAchievements.isNotEmpty()) {
                        item {
                            Text(
                                "Team Achievements",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        items(teamData.teamAchievements) { achievement ->
                            TeamAchievementCard(achievement)
                        }
                    }
                }


                when (type) {
                    "individual" -> item {
                        ComparisonSection(playerData!!)
                    }

                    "team" -> {
                        if (teamData!!.competitiveStats.tournamentHistory.isNotEmpty()) {
                            item {
                                TournamentHistoryCard(teamData!!.competitiveStats.tournamentHistory)
                            }
                        }
                        if (teamData!!.competitiveStats.rivalries.isNotEmpty()) {
                            item {
                                RivalriesCard(teamData!!.competitiveStats.rivalries)
                            }
                        }
                    }
                }
            }
        }
    }
}

