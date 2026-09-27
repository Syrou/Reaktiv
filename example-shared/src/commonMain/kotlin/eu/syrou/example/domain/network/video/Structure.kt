package eu.syrou.example.domain.network.video

import eu.syrou.example.domain.data.VideoItem

interface VideoSource {
    suspend fun fetchVideos(): List<VideoItem>
}
