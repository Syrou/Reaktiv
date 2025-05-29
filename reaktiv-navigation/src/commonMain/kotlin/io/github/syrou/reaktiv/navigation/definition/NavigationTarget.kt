package io.github.syrou.reaktiv.navigation.definition

import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.PrecomputedNavigationData
import io.github.syrou.reaktiv.navigation.exception.RouteNotFoundException


sealed class NavigationTarget {
    data class Path(val path: String) : NavigationTarget()
    data class NavigatableObject(val navigatable: Navigatable) : NavigationTarget()
    data class NavigatableObjectWithGraph(
        val navigatable: Navigatable,
        val preferredGraphId: String
    ) : NavigationTarget()

    
    fun resolve(precomputedData: PrecomputedNavigationData): String {
        return when (this) {
            is Path -> path

            is NavigatableObject -> {
                precomputedData.navigatableToFullPath[navigatable]
                    ?: throw RouteNotFoundException(
                        "Cannot find path to navigatable '${navigatable.route}'. ${
                            precomputedData.routeResolver.getNavigatableNotFoundHint(navigatable)
                        }"
                    )
            }

            is NavigatableObjectWithGraph -> {
                val fullPath = precomputedData.navigatableToFullPath[navigatable]
                    ?: throw RouteNotFoundException(
                        "Cannot find path to navigatable '${navigatable.route}' in any graph. ${
                            precomputedData.routeResolver.getNavigatableNotFoundHint(navigatable)
                        }"
                    )
                val navigatableGraphId = precomputedData.navigatableToGraph[navigatable]
                if (navigatableGraphId != null) {
                    val graphHierarchy = precomputedData.graphHierarchies[navigatableGraphId] ?: emptyList()
                    if (!graphHierarchy.contains(preferredGraphId) && navigatableGraphId != preferredGraphId) {
                        throw RouteNotFoundException(
                            "Navigatable '${navigatable.route}' not found in preferred graph '$preferredGraphId'. " +
                                    "Found in graph '$navigatableGraphId' with hierarchy: ${
                                        graphHierarchy.joinToString(
                                            " > "
                                        )
                                    }"
                        )
                    }
                }

                fullPath
            }
        }
    }

    private fun getAllAvailableRoutes(graphDefinitions: Map<String, NavigationGraph>): List<String> {
        return graphDefinitions.keys.toList()
    }
}