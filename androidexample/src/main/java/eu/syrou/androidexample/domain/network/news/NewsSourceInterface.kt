package eu.syrou.androidexample.domain.network.news

import eu.syrou.androidexample.domain.data.NewsItem

interface NewsSource {

    suspend fun fetchNews(): List<NewsItem>
}
