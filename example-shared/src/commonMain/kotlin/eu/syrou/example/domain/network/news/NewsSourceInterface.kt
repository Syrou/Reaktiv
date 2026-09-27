package eu.syrou.example.domain.network.news

import eu.syrou.example.domain.data.NewsItem

interface NewsSource {

    suspend fun fetchNews(): List<NewsItem>
}
