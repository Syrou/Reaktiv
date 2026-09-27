package eu.syrou.example.domain.network.video

import io.github.syrou.reaktiv.core.util.ReaktivDebug
import kotlinx.coroutines.CancellationException
import eu.syrou.example.domain.data.VideoItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import kotlin.random.Random


class VideosAggregator(private val sources: List<VideoSource>) {
    suspend fun aggregateVideos(): List<VideoItem> = withContext(Dispatchers.Default) {
        val videosBySource = sources.map { source ->
            async {
                try {
                    source.fetchVideos().sortedByDescending { it.pubDate }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    ReaktivDebug.warn("VideosAggregator: Video source ${source::class.simpleName} failed, skipping - ${e.message}")
                    emptyList()
                }
            }
        }.awaitAll()

        interleaveVideos(videosBySource).shuffled().sortedByDescending { it.pubDate }
    }

    private fun interleaveVideos(videosBySource: List<List<VideoItem>>): List<VideoItem> {
        val result = mutableListOf<VideoItem>()
        val iterators = videosBySource.map { it.iterator() }.toMutableList()

        while (iterators.isNotEmpty()) {
            val iterator = iterators.removeAt(0)
            if (iterator.hasNext()) {
                result.add(iterator.next())
                iterators.add(iterator)
            }
        }

        return result
    }
}