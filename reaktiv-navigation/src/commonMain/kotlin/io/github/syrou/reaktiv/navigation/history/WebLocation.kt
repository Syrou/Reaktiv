package io.github.syrou.reaktiv.navigation.history

import io.github.syrou.reaktiv.core.util.isSensitiveKey
import io.github.syrou.reaktiv.navigation.model.NavigationEntry
import io.github.syrou.reaktiv.navigation.util.CommonUrlEncoder
import io.github.syrou.reaktiv.navigation.util.RouteTemplate
import io.github.syrou.reaktiv.navigation.util.normalizePath

internal class WebLocation(private val style: UrlStyle, basePath: String) {

    private val base: String = normalizeBase(basePath)

    val prefix: String
        get() = when (style) {
            UrlStyle.Hash -> "#/"
            UrlStyle.Path -> base
        }

    val basePath: String?
        get() = when (style) {
            UrlStyle.Hash -> null
            UrlStyle.Path -> normalizePath(base)
        }

    fun format(entry: NavigationEntry, hiddenKeys: Set<String> = emptySet()): String {
        val pathParams = RouteTemplate.parse(entry.path).paramNames.toSet()
        val query = entry.params.toMap()
            .filter { (key, value) ->
                key !in pathParams && key !in hiddenKeys && !isSensitiveKey(key) && value.isUrlValue()
            }
            .entries
            .sortedBy { it.key }
            .joinToString("&") { (key, value) -> "${encoder.encodeQuery(key)}=${encoder.encodeQuery(value.toString())}" }
        val suffix = if (query.isEmpty()) "" else "?$query"
        return when (style) {
            UrlStyle.Hash -> "#/${entry.location}$suffix"
            UrlStyle.Path -> "$base${entry.location}$suffix"
        }
    }

    fun parse(pathname: String, search: String, hash: String): String? = when (style) {
        UrlStyle.Hash -> when {
            hash.isEmpty() || hash == "#" || hash == "#/" -> ""
            hash.startsWith("#/") -> hash.substring(2)
            else -> null
        }
        UrlStyle.Path -> {
            val withSlash = if (pathname.endsWith("/")) pathname else "$pathname/"
            when {
                withSlash == base -> search.withoutEmptyQuery()
                pathname.startsWith(base) -> {
                    val route = pathname.removePrefix(base).removeSuffix("/")
                    val withoutIndex = if (route == INDEX_FILE) "" else route
                    withoutIndex + search.withoutEmptyQuery()
                }
                else -> null
            }
        }
    }

    private fun Any.isUrlValue(): Boolean = this is String || this is Number || this is Boolean

    private fun String.withoutEmptyQuery(): String = if (this == "?") "" else this

    private companion object {
        const val INDEX_FILE = "index.html"
        val encoder = CommonUrlEncoder()

        fun normalizeBase(basePath: String): String {
            val withLeading = if (basePath.startsWith("/")) basePath else "/$basePath"
            val directory = if (withLeading.substringAfterLast('/').contains('.')) {
                withLeading.substringBeforeLast('/') + "/"
            } else {
                withLeading
            }
            return if (directory.endsWith("/")) directory else "$directory/"
        }
    }
}
