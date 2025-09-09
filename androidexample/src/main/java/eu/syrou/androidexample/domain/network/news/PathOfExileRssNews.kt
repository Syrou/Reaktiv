package eu.syrou.androidexample.domain.network.news

import eu.syrou.androidexample.domain.data.NewsItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.Month
import kotlinx.datetime.UtcOffset
import kotlinx.datetime.toInstant
import kotlin.time.Clock.System


class RssNewsSource(private val url: String, private val sourceName: String) : BaseNewsSource() {
    override suspend fun fetchNews(): List<NewsItem> = withContext(Dispatchers.IO) {
        val content = getAndParseRss(url)
        parseRssContent(content)
    }

    private fun parseRssContent(content: String): List<NewsItem> {
        val items = mutableListOf<NewsItem>()
        val itemRegex = "<item>(.+?)</item>".toRegex(RegexOption.DOT_MATCHES_ALL)
        val titleRegex = "<title>(.+?)</title>".toRegex()
        val linkRegex = "<link>(.+?)</link>".toRegex()
        val descriptionRegex = "<description>(.+?)</description>".toRegex()
        val pubDateRegex = "<pubDate>(.+?)</pubDate>".toRegex()

        itemRegex.findAll(content).forEach { matchResult ->
            val itemContent = matchResult.groupValues[1]
            val title = titleRegex.find(itemContent)?.groupValues?.get(1) ?: ""
            val link = linkRegex.find(itemContent)?.groupValues?.get(1) ?: ""
            val description = descriptionRegex.find(itemContent)?.groupValues?.get(1) ?: ""
            val pubDateString = pubDateRegex.find(itemContent)?.groupValues?.get(1) ?: ""

            val pubDate = try {
                parseDateString(pubDateString.trim())
            } catch (e: Exception) {
                System.now()
            }

            items.add(
                NewsItem(
                    title = title.trim(),
                    link = link.trim(),
                    description = description.trim().replace(Regex("<!\\[CDATA\\[|]]>"), "")
                        .replace(Regex("<.*?>"), ""),
                    pubDate = pubDate,
                    author = "Unknown",
                    source = sourceName
                )
            )
        }

        return items.sortedBy { it.pubDate }
    }

    private fun parseDateString(dateString: String): kotlin.time.Instant {
        val parts = dateString.split(" ")
        if (parts.size < 6) throw IllegalArgumentException("Invalid date format")

        val day = parts[1].toInt()
        val month = parseMonth(parts[2])
        val year = parts[3].toInt()
        val (hour, minute, second) = parts[4].split(":").map { it.toInt() }
        val offset = parseOffset(parts[5])

        return LocalDateTime(year, month, day, hour, minute, second).toInstant(offset)
    }

    private fun parseMonth(monthStr: String): Month = when (monthStr.lowercase().take(3)) {
        "jan" -> Month.JANUARY
        "feb" -> Month.FEBRUARY
        "mar" -> Month.MARCH
        "apr" -> Month.APRIL
        "may" -> Month.MAY
        "jun" -> Month.JUNE
        "jul" -> Month.JULY
        "aug" -> Month.AUGUST
        "sep" -> Month.SEPTEMBER
        "oct" -> Month.OCTOBER
        "nov" -> Month.NOVEMBER
        "dec" -> Month.DECEMBER
        else -> throw IllegalArgumentException("Invalid month: $monthStr")
    }

    private fun parseOffset(offsetStr: String): UtcOffset {
        val hours = offsetStr.substring(1, 3).toInt()
        val minutes = offsetStr.substring(3, 5).toInt()
        return if (offsetStr.startsWith("+")) {
            UtcOffset(hours = hours, minutes = minutes)
        } else {
            UtcOffset(hours = -hours, minutes = -minutes)
        }
    }
}

