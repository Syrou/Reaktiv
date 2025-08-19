package eu.syrou.androidexample.domain.model

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

@Serializable
data class PlayerProfile(
    val playerId: String,
    val name: String,
    val rank: Int,
    val score: Long,
    val level: Int,
    val winRate: Double,
    val gamesPlayed: Int,
    val gamesWon: Int,
    // in seconds
    val averageGameTime: Long,
    val achievements: List<Achievement> = emptyList(),
    val stats: PlayerStats,
    val lastActive: Instant
)

@Serializable
data class Achievement(
    val id: String,
    val name: String,
    val description: String,
    val unlockedAt: Instant,
    val rarity: AchievementRarity
)

@Serializable
enum class AchievementRarity {
    COMMON, UNCOMMON, RARE, EPIC, LEGENDARY
}

@Serializable
data class PlayerStats(
    val totalDamageDealt: Long,
    val totalHealingDone: Long,
    val highestScore: Long,
    val longestWinStreak: Int,
    val favoriteCharacter: String,
    // in seconds
    val totalPlayTime: Long,
    val rankingHistory: List<RankingEntry> = emptyList()
)

@Serializable
data class RankingEntry(
    val rank: Int,
    val recordedAt: Instant,
    val season: String
)

@Serializable
data class IndividualStatsData(
    val playerId: String,
    val playerName: String,
    val detailedStats: DetailedIndividualStats,
    val comparison: PlayerComparison,
    val progressionData: ProgressionData
)

@Serializable
data class DetailedIndividualStats(
    val combatStats: CombatStats,
    val economyStats: EconomyStats,
    val socialStats: SocialStats,
    val performanceMetrics: PerformanceMetrics
)

@Serializable
data class CombatStats(
    val killDeathRatio: Double,
    val averageDamagePerGame: Double,
    val criticalHitRate: Double,
    val mostUsedWeapon: String,
    val totalKills: Int,
    val totalDeaths: Int
)

@Serializable
data class EconomyStats(
    val totalCurrencyEarned: Long,
    val totalCurrencySpent: Long,
    val mostExpensivePurchase: String,
    val tradingProfit: Long,
    val itemsCollected: Int
)

@Serializable
data class SocialStats(
    val friendsCount: Int,
    val guildMemberships: List<String>,
    val messagesExchanged: Int,
    val helpfulVotes: Int,
    val reputationScore: Int
)

@Serializable
data class PerformanceMetrics(
    // in milliseconds
    val averageReactionTime: Double,
    val accuracyPercentage: Double,
    val consistencyScore: Double,
    val clutchPlays: Int,
    val perfectGames: Int
)

@Serializable
data class PlayerComparison(
    val vsGlobalAverage: ComparisonMetrics,
    val vsRankAverage: ComparisonMetrics,
    val vsFriends: ComparisonMetrics
)

@Serializable
data class ComparisonMetrics(
    val scoreMultiplier: Double,
    val winRateComparison: Double,
    val skillRating: Double,
    val improvementRate: Double
)

@Serializable
data class ProgressionData(
    val currentLevel: Int,
    val experiencePoints: Long,
    val experienceToNextLevel: Long,
    val skillProgression: Map<String, SkillProgress>,
    val milestones: List<Milestone>
)

@Serializable
data class SkillProgress(
    val skillName: String,
    val currentLevel: Int,
    val experience: Long,
    val masteryPercentage: Double
)

@Serializable
data class Milestone(
    val name: String,
    val description: String,
    val completedAt: Instant?,
    val requirements: String,
    val reward: String
)

@Serializable
data class TeamStatsData(
    val teamId: String,
    val teamName: String,
    val members: List<TeamMember>,
    val teamPerformance: TeamPerformance,
    val teamAchievements: List<TeamAchievement>,
    val competitiveStats: CompetitiveStats
)

@Serializable
data class TeamMember(
    val playerId: String,
    val name: String,
    val role: String,
    val joinedAt: Instant,
    val contributionScore: Double,
    val isLeader: Boolean,
    val isActive: Boolean
)

@Serializable
data class TeamPerformance(
    val overallRating: Double,
    val teamworkScore: Double,
    val coordinationRating: Double,
    val winStreakRecord: Int,
    val averageGameDuration: Long,
    val strongestGameMode: String,
    val weakestGameMode: String
)

@Serializable
data class TeamAchievement(
    val achievementId: String,
    val name: String,
    val description: String,
    val unlockedAt: Instant,
    val contributingMembers: List<String>,
    val difficulty: TeamAchievementDifficulty
)

@Serializable
enum class TeamAchievementDifficulty {
    BRONZE, SILVER, GOLD, PLATINUM, DIAMOND
}

@Serializable
data class CompetitiveStats(
    val currentSeason: SeasonStats,
    val previousSeasons: List<SeasonStats>,
    val tournamentHistory: List<TournamentResult>,
    val rivalries: List<Rivalry>
)

@Serializable
data class SeasonStats(
    val season: String,
    val rank: Int,
    val points: Long,
    val matchesPlayed: Int,
    val matchesWon: Int,
    val matchesLost: Int,
    val winRate: Double
)

@Serializable
data class TournamentResult(
    val tournamentName: String,
    val placement: Int,
    val totalTeams: Int,
    val prizeWon: Long,
    val completedAt: Instant
)

@Serializable
data class Rivalry(
    val opponentTeamName: String,
    val matchesPlayed: Int,
    val matchesWon: Int,
    val lastMatch: Instant,
    val intensity: RivalryIntensity
)

@Serializable
enum class RivalryIntensity {
    CASUAL, MODERATE, HEATED, FIERCE, LEGENDARY
}