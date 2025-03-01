package io.github.syrou.reaktiv.navigation.util

object PathUtil {
    fun isParameterSegment(segment: String): Boolean {
        val result = segment.startsWith(":") || (segment.startsWith("{") && segment.endsWith("}"))
        return result
    }

    fun extractParameterName(segment: String): String {
        val result = when {
            segment.startsWith(":") -> segment.substring(1)
            segment.startsWith("{") && segment.endsWith("}") -> segment.substring(1, segment.length - 1)
            else -> throw IllegalArgumentException("Not a parameter segment: $segment")
        }
        return result
    }

    fun matchPath(routeTemplate: String, actualPath: String): Boolean {
        val templateParts = routeTemplate.split("/")
        val pathParts = actualPath.split("/")

        if (templateParts.size != pathParts.size) {
            return false
        }

        val result = templateParts.zip(pathParts).all { (template, actual) ->
            val matches = template == actual || isParameterSegment(template)
            matches
        }

        return result
    }

    fun getParentPath(path: String): String {
        val segments = path.split('/').filter { it.isNotEmpty() }
        val result = if (segments.size <= 1) "" else segments.dropLast(1).joinToString("/")

        return result
    }

    fun getPathSegments(path: String): List<String> {
        val result = path.split('/').filter { it.isNotEmpty() }
        return result
    }

    fun isDirectChildOf(childPath: String, parentPath: String): Boolean {
        if (parentPath.isEmpty()) {
            val result = getPathSegments(childPath).size == 1
            return result
        }

        val parentSegments = getPathSegments(parentPath)
        val childSegments = getPathSegments(childPath)

        val result = childSegments.size == parentSegments.size + 1 &&
                childSegments.take(parentSegments.size).joinToString("/") == parentSegments.joinToString("/")

        return result
    }
}