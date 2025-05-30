package eu.syrou.androidexample.domain.network.video

import eu.syrou.androidexample.domain.data.VideoItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlin.random.Random


class VideosAggregator(private val sources: List<VideoSource>) {
    suspend fun aggregateVideos(): List<VideoItem> = withContext(Dispatchers.Default) {
        val videosBySource = sources.map { source ->
            async { source.fetchVideos().sortedByDescending { it.pubDate } }
        }.awaitAll()

        interleaveVideos(videosBySource).shuffled().sortedByDescending { it.pubDate }
    }

    private fun interleaveVideos(videosBySource: List<List<VideoItem>>): List<VideoItem> {
        val result = mutableListOf<VideoItem>()
        val iterators = videosBySource.map { it.iterator() }.toMutableList()

        while (iterators.isNotEmpty()) {
            val iterator = iterators.removeFirst()
            if (iterator.hasNext()) {
                result.add(iterator.next())
                iterators.add(iterator)
            }
        }

        return result
    }
}