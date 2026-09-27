@file:OptIn(ExperimentalAtomicApi::class)

package io.github.syrou.reaktiv.navigation.util

import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.update

internal class RouteTemplate private constructor(
    val template: String,
    private val segments: List<Segment>
) {
    val paramNames: List<String> = segments.flatMap { it.paramNames }

    val isParameterized: Boolean get() = paramNames.isNotEmpty()

    val segmentCount: Int get() = segments.size

    fun match(path: String): Map<String, String>? {
        val parts = splitPath(path)
        if (parts.size != segments.size) return null
        val values = LinkedHashMap<String, String>()
        for (index in segments.indices) {
            val decoded = encoder.decodePathSegment(parts[index])
            when (val segment = segments[index]) {
                is Segment.Static -> if (decoded != segment.text) return null
                is Segment.Param -> {
                    if (decoded.isEmpty()) return null
                    values[segment.name] = decoded
                }
                is Segment.Mixed -> {
                    val result = segment.regex.matchEntire(decoded) ?: return null
                    segment.names.forEachIndexed { i, name -> values[name] = result.groupValues[i + 1] }
                }
            }
        }
        return values
    }

    fun missing(value: (String) -> String?): List<String> = paramNames.filter { value(it) == null }.distinct()

    fun fill(value: (String) -> String?): Fill {
        val missing = missing(value)
        return if (missing.isEmpty()) Fill.Filled(render(value, encoded = true)) else Fill.Missing(missing)
    }

    sealed interface Fill {
        data class Filled(val location: String) : Fill
        data class Missing(val names: List<String>) : Fill
    }

    fun render(value: (String) -> String?, encoded: Boolean): String =
        segments.joinToString("/") { segment ->
            segment.parts.joinToString("") { part ->
                when (part) {
                    is Part.Literal -> if (encoded) encoder.encodePath(part.text) else part.text
                    is Part.Placeholder -> value(part.name)
                        ?.let { if (encoded) encoder.encodePath(it) else it }
                        ?: "{${part.name}}"
                }
            }
        }

    fun sameShapeAs(other: RouteTemplate): Boolean =
        segments.size == other.segments.size &&
            segments.indices.all { segments[it].shape == other.segments[it].shape }

    private sealed class Part {
        data class Literal(val text: String) : Part()
        data class Placeholder(val name: String) : Part()
    }

    private sealed class Segment(val parts: List<Part>) {
        val paramNames: List<String> get() = parts.filterIsInstance<Part.Placeholder>().map { it.name }
        abstract val rank: Int
        abstract val shape: String

        class Static(val text: String) : Segment(listOf(Part.Literal(text))) {
            override val rank: Int get() = 3
            override val shape: String get() = "s:$text"
        }

        class Param(val name: String) : Segment(listOf(Part.Placeholder(name))) {
            override val rank: Int get() = 1
            override val shape: String get() = "p"
        }

        class Mixed(parts: List<Part>) : Segment(parts) {
            val names: List<String> = paramNames
            val regex: Regex = Regex(
                parts.joinToString("", prefix = "^", postfix = "$") { part ->
                    when (part) {
                        is Part.Literal -> Regex.escape(part.text)
                        is Part.Placeholder -> "(.+)"
                    }
                }
            )
            override val rank: Int get() = 2
            override val shape: String
                get() = parts.joinToString("") { if (it is Part.Literal) it.text else "{}" }
        }
    }

    internal companion object {
        private val encoder = CommonUrlEncoder()
        private val placeholder = Regex("\\{([^}]+)\\}")

        val specificity: Comparator<RouteTemplate> = Comparator { a, b ->
            val shared = minOf(a.segments.size, b.segments.size)
            for (index in 0 until shared) {
                val difference = b.segments[index].rank - a.segments[index].rank
                if (difference != 0) return@Comparator difference
            }
            a.segments.size - b.segments.size
        }

        private const val PARSE_CACHE_LIMIT = 512
        private val parsed = AtomicReference<Map<String, RouteTemplate>>(emptyMap())

        fun parse(template: String): RouteTemplate {
            parsed.load()[template]?.let { return it }
            val fresh = parseUncached(template)
            parsed.update { current ->
                if (current.size >= PARSE_CACHE_LIMIT || template in current) current else current + (template to fresh)
            }
            return fresh
        }

        private fun parseUncached(template: String): RouteTemplate {
            val clean = normalizePath(template)
            val segments = if (clean.isEmpty()) emptyList() else clean.split('/').map(::parseSegment)
            return RouteTemplate(clean, segments)
        }

        fun splitPath(path: String): List<String> {
            val clean = normalizePath(path)
            return if (clean.isEmpty()) emptyList() else clean.split('/')
        }

        fun normalizeLocation(path: String): String =
            splitPath(path).joinToString("/") { encoder.encodePath(encoder.decodePathSegment(it)) }

        private fun parseSegment(text: String): Segment {
            val matches = placeholder.findAll(text).toList()
            if (matches.isEmpty()) return Segment.Static(text)
            val only = matches.singleOrNull()
            if (only != null && only.range.first == 0 && only.range.last == text.lastIndex) {
                return Segment.Param(only.groupValues[1])
            }
            val parts = mutableListOf<Part>()
            var cursor = 0
            for (match in matches) {
                if (match.range.first > cursor) parts += Part.Literal(text.substring(cursor, match.range.first))
                parts += Part.Placeholder(match.groupValues[1])
                cursor = match.range.last + 1
            }
            if (cursor < text.length) parts += Part.Literal(text.substring(cursor))
            return Segment.Mixed(parts)
        }
    }
}
