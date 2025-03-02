package io.github.syrou.reaktiv.navigation.util

object PathUtil {
    fun isParameterSegment(segment: String): Boolean {
        val result = segment.startsWith(":") || (segment.startsWith("{") && segment.endsWith("}"))
        println("DEBUG [PathUtil.isParameterSegment] segment: '$segment', result: $result")
        return result
    }

    fun extractParameterName(segment: String): String {
        val result = when {
            segment.startsWith(":") -> segment.substring(1)
            segment.startsWith("{") && segment.endsWith("}") -> segment.substring(1, segment.length - 1)
            else -> throw IllegalArgumentException("Not a parameter segment: $segment")
        }
        println("DEBUG [PathUtil.extractParameterName] segment: '$segment', result: '$result'")
        return result
    }

    fun matchPath(routeTemplate: String, actualPath: String): Boolean {
        println("DEBUG [PathUtil.matchPath] routeTemplate: '$routeTemplate', actualPath: '$actualPath'")

        val templateParts = routeTemplate.split("/")
        val pathParts = actualPath.split("/")

        println("DEBUG [PathUtil.matchPath] templateParts: $templateParts, pathParts: $pathParts")

        if (templateParts.size != pathParts.size) {
            println("DEBUG [PathUtil.matchPath] different sizes, no match")
            return false
        }

        val result = templateParts.zip(pathParts).all { (template, actual) ->
            val matches = template == actual || isParameterSegment(template)
            println("DEBUG [PathUtil.matchPath] comparing: '$template' vs '$actual', matches: $matches")
            matches
        }

        println("DEBUG [PathUtil.matchPath] final result: $result")
        return result
    }

    fun getParentPath(path: String): String {
        println("DEBUG [PathUtil.getParentPath] path: '$path'")

        val segments = path.split('/').filter { it.isNotEmpty() }
        println("DEBUG [PathUtil.getParentPath] segments: $segments")

        val result = if (segments.size <= 1) "" else segments.dropLast(1).joinToString("/")
        println("DEBUG [PathUtil.getParentPath] result: '$result'")

        return result
    }

    fun getPathSegments(path: String): List<String> {
        val result = path.split('/').filter { it.isNotEmpty() }
        println("DEBUG [PathUtil.getPathSegments] path: '$path', result: $result")
        return result
    }

    fun isDirectChildOf(childPath: String, parentPath: String): Boolean {
        println("DEBUG [PathUtil.isDirectChildOf] childPath: '$childPath', parentPath: '$parentPath'")

        if (parentPath.isEmpty()) {
            val result = getPathSegments(childPath).size == 1
            println("DEBUG [PathUtil.isDirectChildOf] empty parent, result: $result")
            return result
        }

        val parentSegments = getPathSegments(parentPath)
        val childSegments = getPathSegments(childPath)

        println("DEBUG [PathUtil.isDirectChildOf] parentSegments: $parentSegments, childSegments: $childSegments")

        val result = childSegments.size == parentSegments.size + 1 &&
                childSegments.take(parentSegments.size).joinToString("/") == parentSegments.joinToString("/")

        println("DEBUG [PathUtil.isDirectChildOf] result: $result")
        return result
    }
}