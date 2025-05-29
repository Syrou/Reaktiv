package eu.syrou.androidexample.ui.screen.home.leaderboard.compose.team

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Badge
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import eu.syrou.androidexample.domain.model.Rivalry
import eu.syrou.androidexample.domain.model.RivalryIntensity
import eu.syrou.androidexample.domain.model.SeasonStats
import eu.syrou.androidexample.domain.model.TeamAchievement
import eu.syrou.androidexample.domain.model.TeamAchievementDifficulty
import eu.syrou.androidexample.domain.model.TeamMember
import eu.syrou.androidexample.domain.model.TeamPerformance
import eu.syrou.androidexample.domain.model.TeamStatsData
import eu.syrou.androidexample.domain.model.TournamentResult

@Composable
fun TeamHeader(teamData: TeamStatsData) {
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
                text = teamData.teamName,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "${teamData.members.size} Members",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = "Overall Rating: ${String.format("%.1f", teamData.teamPerformance.overallRating)}/5.0",
                style = MaterialTheme.typography.bodyLarge
            )

            Spacer(modifier = Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = (teamData.teamPerformance.overallRating / 5.0).toFloat(),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp),
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
fun TeamPerformanceCard(performance: TeamPerformance) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                "Team Performance",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                PerformanceMetric(
                    "Teamwork Score",
                    performance.teamworkScore,
                    MaterialTheme.colorScheme.primary
                )
                PerformanceMetric(
                    "Coordination Rating",
                    performance.coordinationRating,
                    MaterialTheme.colorScheme.secondary
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    StatItem("Win Streak Record", "${performance.winStreakRecord}")
                    StatItem("Avg Game Duration", "${performance.averageGameDuration / 60} min")
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    StatItem("Strongest Mode", performance.strongestGameMode)
                    StatItem("Weakest Mode", performance.weakestGameMode)
                }
            }
        }
    }
}

@Composable
fun PerformanceMetric(
    label: String,
    value: Double,
    color: Color
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                "${String.format("%.1f", value * 100)}%",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold
            )
        }
        LinearProgressIndicator(
            progress = value.toFloat(),
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp),
            color = color
        )
    }
}

@Composable
fun CurrentSeasonCard(seasonStats: SeasonStats) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                "Current Season - ${seasonStats.season}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                StatItem("Rank", "#${seasonStats.rank}")
                StatItem("Points", "${seasonStats.points}")
                StatItem("Win Rate", "${String.format("%.1f", seasonStats.winRate * 100)}%")
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                StatItem("Matches Played", "${seasonStats.matchesPlayed}")
                StatItem("Wins", "${seasonStats.matchesWon}")
                StatItem("Losses", "${seasonStats.matchesLost}")
            }
        }
    }
}

@Composable
fun TeamMembersCard(members: List<TeamMember>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                "Team Members",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            members.forEach { member ->
                TeamMemberItem(member)
                if (member != members.last()) {
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
fun TeamMemberItem(member: TeamMember) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape),
            color = if (member.isActive)
                MaterialTheme.colorScheme.primary
            else
                MaterialTheme.colorScheme.surfaceVariant
        ) {
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    member.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                if (member.isLeader) {
                    Icon(
                        Icons.Default.Star,
                        contentDescription = "Leader",
                        modifier = Modifier
                            .size(16.dp)
                            .padding(start = 4.dp),
                        tint = Color(0xFFFFD700) // Gold color
                    )
                }
            }
            Text(
                member.role,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Column(horizontalAlignment = Alignment.End) {
            Text(
                "${String.format("%.1f", member.contributionScore * 100)}%",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                "Contribution",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun TeamAchievementCard(achievement: TeamAchievement) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when (achievement.difficulty) {
                TeamAchievementDifficulty.BRONZE -> Color(0xFFCD7F32).copy(alpha = 0.1f)
                TeamAchievementDifficulty.SILVER -> Color(0xFFC0C0C0).copy(alpha = 0.1f)
                TeamAchievementDifficulty.GOLD -> Color(0xFFFFD700).copy(alpha = 0.1f)
                TeamAchievementDifficulty.PLATINUM -> Color(0xFFE5E4E2).copy(alpha = 0.1f)
                TeamAchievementDifficulty.DIAMOND -> Color(0xFFB9F2FF).copy(alpha = 0.1f)
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
                Text(
                    text = "Contributors: ${achievement.contributingMembers.size}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Badge(
                containerColor = when (achievement.difficulty) {
                    TeamAchievementDifficulty.BRONZE -> Color(0xFFCD7F32)
                    TeamAchievementDifficulty.SILVER -> Color(0xFFC0C0C0)
                    TeamAchievementDifficulty.GOLD -> Color(0xFFFFD700)
                    TeamAchievementDifficulty.PLATINUM -> Color(0xFFE5E4E2)
                    TeamAchievementDifficulty.DIAMOND -> Color(0xFFB9F2FF)
                }
            ) {
                Text(
                    achievement.difficulty.name,
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}

@Composable
fun TournamentHistoryCard(tournaments: List<TournamentResult>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                "Tournament History",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            tournaments.take(5).forEach { tournament ->
                TournamentItem(tournament)
                if (tournament != tournaments.last()) {
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
fun TournamentItem(tournament: TournamentResult) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                tournament.tournamentName,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                "Placement: #${tournament.placement} of ${tournament.totalTeams}",
                style = MaterialTheme.typography.bodySmall
            )
        }

        if (tournament.prizeWon > 0) {
            Text(
                "Prize: ${tournament.prizeWon}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun RivalriesCard(rivalries: List<Rivalry>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                "Team Rivalries",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            rivalries.forEach { rivalry ->
                RivalryItem(rivalry)
                if (rivalry != rivalries.last()) {
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
fun RivalryItem(rivalry: Rivalry) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                rivalry.opponentTeamName,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                "Record: ${rivalry.matchesWon}-${rivalry.matchesPlayed - rivalry.matchesWon}",
                style = MaterialTheme.typography.bodySmall
            )
        }

        Badge(
            containerColor = when (rivalry.intensity) {
                RivalryIntensity.CASUAL -> MaterialTheme.colorScheme.surfaceVariant
                RivalryIntensity.MODERATE -> Color(0xFFFFA500) // Orange
                RivalryIntensity.HEATED -> Color(0xFFFF6347) // Tomato
                RivalryIntensity.FIERCE -> Color(0xFFDC143C) // Crimson
                RivalryIntensity.LEGENDARY -> Color(0xFF8B0000) // Dark Red
            }
        ) {
            Text(
                rivalry.intensity.name,
                style = MaterialTheme.typography.labelSmall
            )
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
        horizontalAlignment = Alignment.CenterHorizontally
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