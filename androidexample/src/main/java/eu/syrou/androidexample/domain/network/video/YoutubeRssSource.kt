package eu.syrou.androidexample.domain.network.video

import eu.syrou.androidexample.domain.data.VideoItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.time.Instant

class YouTubeRssSource(private val url: String, private val source: String) : BaseVideoSource() {

    private val poeKeywords = listOf(
        "path of exile", "poe", "exilecon",
        "wraeclast", "atlas", "hideout",
        "exalted orb", "chaos orb", "divine orb",
        "delirium", "metamorph", "blight",
        "legion", "synthesis", "betrayal",
        "delve", "incursion", "bestiary",
        "abyss", "harbinger", "legacy",
        "breach", "essence", "prophecy",
        "torment", "bloodlines", "rampage",
        "onslaught", "build guide", "league start",
        "boss fight", "mapping",
        "currency farming", "crafting guide",
        "unique items", "gem setup", "skill tree",
        "ascendancy", "passive points", "jewels",
        "flasks", "pantheon", "labyrinth",
        "uber elder", "shaper", "sirus",
        "atziri", "maven", "conquerors",
        "awakener", "heist", "ritual",
        "ultimatum", "expedition", "scourge",
        "archnemesis", "sentinel", "kalandra",
        "sanctum", "necropolis",
        "path of building", "new league", "trickster",
        "champion", "hierophant", "elementalist",
        "assassin", "occultist", "guardian", "berserker",
        "cheiftain", "juggernaut", "slayer", "gladiator",
        "raider", "deadeye", "pathfinder", "scion",
        "saboteur", "inquisitor", "necromancer"
    )

    override suspend fun fetchVideos(): List<VideoItem> = withContext(Dispatchers.IO) {
        val content = getAndParseRss(url)
        parseRssContent(content)
    }

    private fun parseRssContent(content: String): List<VideoItem> {
        val items = mutableListOf<VideoItem>()
        val channelRegex = "<title>(.+?)</title>".toRegex()
        val entryRegex = "<entry>(.+?)</entry>".toRegex(RegexOption.DOT_MATCHES_ALL)
        val titleRegex = "<title>(.+?)</title>".toRegex()
        val linkRegex = "<link rel=\"alternate\" href=\"(.+?)\"/>".toRegex()
        val descriptionRegex = "<media:description>(.+?)</media:description>".toRegex()
        val pubDateRegex = "<published>(.+?)</published>".toRegex()
        val thumbnailRegex = "<media:thumbnail url=\"(.+?)\"".toRegex()
        val videoIdRegex = "<yt:videoId>(.+?)</yt:videoId>".toRegex()

        val channelName = channelRegex.find(content)?.groupValues?.get(1) ?: "Unknown Channel"

        entryRegex.findAll(content).forEach { matchResult ->
            val entryContent = matchResult.groupValues[1]
            val title = titleRegex.find(entryContent)?.groupValues?.get(1) ?: ""
            val link = linkRegex.find(entryContent)?.groupValues?.get(1) ?: ""
            val description = descriptionRegex.find(entryContent)?.groupValues?.get(1) ?: ""

            if (isPoeRelated(title, description)) {

                val pubDateString = pubDateRegex.find(entryContent)?.groupValues?.get(1) ?: ""
                val thumbnailUrl = thumbnailRegex.find(entryContent)?.groupValues?.get(1) ?: ""
                val videoId = videoIdRegex.find(entryContent)?.groupValues?.get(1) ?: ""

                val pubDate = try {
                    Instant.parse(pubDateString)
                } catch (e: Exception) {
                    Clock.System.now()
                }

                items.add(
                    VideoItem(
                        title = title.trim(),
                        link = link.trim(),
                        description = description.trim(),
                        pubDate = pubDate,
                        channelName = channelName,
                        thumbnailUrl = thumbnailUrl,
                        videoId = videoId
                    )
                )
            }
        }

        return items.sortedByDescending { it.pubDate }
    }

    private fun isPoeRelated(title: String, description: String): Boolean {
        val combinedText = ("$title $description").lowercase()
        return poeKeywords.any { keyword -> combinedText.contains(keyword) }
    }
}