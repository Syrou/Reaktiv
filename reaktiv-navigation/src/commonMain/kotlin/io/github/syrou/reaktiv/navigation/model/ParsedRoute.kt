package io.github.syrou.reaktiv.navigation.model

import io.github.syrou.reaktiv.navigation.definition.NavigationGraph

data class ParsedRoute(
    val graphId: String?,
    val route: String,
    val params: Map<String, Any>,
    val isGraphSwitch: Boolean = false
) {
    companion object {
        fun parse(
            fullRoute: String,
            currentGraphId: String,
            availableGraphs: Map<String, NavigationGraph>
        ): ParsedRoute {
            val cleanRoute = fullRoute.trimStart('/')

            if (cleanRoute.isEmpty()) {
                return ParsedRoute(
                    graphId = currentGraphId,
                    route = "",
                    params = emptyMap(),
                    isGraphSwitch = false
                )
            }

            // Split route into path and query parts
            val (pathPart, queryPart) = cleanRoute.split("?", limit = 2).let {
                if (it.size == 2) it[0] to it[1] else it[0] to ""
            }

            val pathSegments = pathPart.split("/").filter { it.isNotEmpty() }
            val queryParams = extractQueryParameters(queryPart)

            // Check for graph switching patterns
            if (pathSegments.size == 1 && availableGraphs.containsKey(pathSegments[0])) {
                // Pure graph switch: "graphId"
                return ParsedRoute(
                    graphId = pathSegments[0],
                    route = "",
                    params = queryParams,
                    isGraphSwitch = true
                )
            }

            if (pathSegments.size >= 2 && availableGraphs.containsKey(pathSegments[0])) {
                // Graph + route: "graphId/route" or "graphId/nested/route"
                val graphId = pathSegments[0]
                val routePath = pathSegments.drop(1).joinToString("/")

                return ParsedRoute(
                    graphId = graphId,
                    route = routePath,
                    params = queryParams,
                    isGraphSwitch = graphId != currentGraphId
                )
            }

            // Route within current graph
            return ParsedRoute(
                graphId = null,
                route = pathSegments.joinToString("/"),
                params = queryParams,
                isGraphSwitch = false
            )
        }

        private fun extractQueryParameters(query: String): Map<String, Any> {
            if (query.isEmpty()) return emptyMap()

            return query.split("&")
                .filter { it.isNotEmpty() }
                .mapNotNull { param ->
                    val parts = param.split("=", limit = 2)
                    if (parts.size == 2) {
                        val key = parts[0].trim()
                        val value = parts[1].trim()
                        if (key.isNotEmpty()) {
                            key to parseParameterValue(value)
                        } else null
                    } else null
                }
                .toMap()
        }

        private fun parseParameterValue(value: String): Any {
            return when {
                value.equals("true", ignoreCase = true) -> true
                value.equals("false", ignoreCase = true) -> false
                value.toIntOrNull() != null -> value.toInt()
                value.toDoubleOrNull() != null -> value.toDouble()
                else -> value
            }
        }
    }
}