package io.github.syrou.reaktiv.navigation.util

object PathUtil {
    fun isParameterSegment(segment: String): Boolean {
        return segment.startsWith(":") || (segment.startsWith("{") && segment.endsWith("}"))
    }

    fun extractParameterName(segment: String): String {
        return when {
            segment.startsWith(":") -> segment.substring(1)
            segment.startsWith("{") && segment.endsWith("}") -> segment.substring(1, segment.length - 1)
            else -> throw IllegalArgumentException("Not a parameter segment: $segment")
        }
    }

    fun matchPath(routeTemplate: String, actualPath: String): Boolean {
        val templateParts = routeTemplate.split("/")
        val pathParts = actualPath.split("/")

        if (templateParts.size != pathParts.size) return false

        return templateParts.zip(pathParts).all { (template, actual) ->
            template == actual || isParameterSegment(template)
        }
    }

    fun getParentPath(path: String): String {
        val segments = path.split('/').filter { it.isNotEmpty() }
        return if (segments.size <= 1) "" else segments.dropLast(1).joinToString("/")
    }

    fun getPathSegments(path: String): List<String> {
        return path.split('/').filter { it.isNotEmpty() }
    }

    fun isDirectChildOf(childPath: String, parentPath: String): Boolean {
        if (parentPath.isEmpty()) {
            return getPathSegments(childPath).size == 1
        }

        val parentSegments = getPathSegments(parentPath)
        val childSegments = getPathSegments(childPath)

        return childSegments.size == parentSegments.size + 1 &&
                childSegments.take(parentSegments.size).joinToString("/") == parentSegments.joinToString("/")
    }
}