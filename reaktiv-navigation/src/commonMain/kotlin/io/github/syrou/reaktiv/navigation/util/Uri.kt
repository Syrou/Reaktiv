package io.github.syrou.reaktiv.navigation.util

private val routeParamRegex = Regex("\\{([^}]+)\\}")

/**
 * Extracts `{paramName}` placeholder names from a route template in declaration order.
 *
 * Example: `"user/{id}/posts/{postId}"` → `["id", "postId"]`
 */
internal fun extractRouteParameterNames(route: String): List<String> =
    routeParamRegex.findAll(route).map { it.groupValues[1] }.toList()

/**
 * Compiles a route template into a [Regex] where each `{paramName}` placeholder becomes
 * a `([^/]+)` capture group and all other regex-special characters are escaped.
 *
 * Works for plain paths (`"user/{id}"`) as well as full URL patterns
 * (`"{scheme}://{host}/path/{token}"`).
 */
internal fun createRouteRegex(route: String): Regex {
    val escapedRoute = route
        .replace(".", "\\.")
        .replace("+", "\\+")
        .replace("*", "\\*")
        .replace("?", "\\?")
        .replace("^", "\\^")
        .replace("$", "\\$")
        .replace("(", "\\(")
        .replace(")", "\\)")
        .replace("[", "\\[")
        .replace("]", "\\]")
        .replace("|", "\\|")
    val pattern = escapedRoute.replace(Regex("\\{[^}]+\\}"), "([^/]+)")
    return Regex("^$pattern$")
}

/**
 * Parse URL-style string with query parameters
 * Returns a Pair of (cleanPath, queryParams)
 */
fun parseUrlWithQueryParams(url: String): Pair<String, Map<String, String>> {
    val urlEncoder = CommonUrlEncoder()

    // Split on first occurrence of '?'
    val parts = url.split("?", limit = 2)
    val cleanPath = parts[0].trimEnd('/')

    val queryParams = if (parts.size > 1) {
        parseQueryString(parts[1], urlEncoder)
    } else {
        emptyMap()
    }

    return cleanPath to queryParams
}

/**
 * Parse query string into map of parameters
 */
private fun parseQueryString(queryString: String, urlEncoder: CommonUrlEncoder): Map<String, String> {
    if (queryString.isBlank()) return emptyMap()

    return queryString.split("&")
        .filter { it.isNotBlank() }
        .mapNotNull { param ->
            val parts = param.split("=", limit = 2)
            if (parts.size == 2) {
                val key = urlEncoder.decode(parts[0])
                val value = urlEncoder.decode(parts[1])
                key to value
            } else if (parts.size == 1) {
                // Handle parameters without values (e.g., "?debug&verbose")
                val key = urlEncoder.decode(parts[0])
                key to "true"
            } else {
                null
            }
        }
        .toMap()
}