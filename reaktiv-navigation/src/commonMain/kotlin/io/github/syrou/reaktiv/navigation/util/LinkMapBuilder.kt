package io.github.syrou.reaktiv.navigation.util

import io.github.syrou.reaktiv.navigation.PrecomputedNavigationData
import io.github.syrou.reaktiv.navigation.definition.Modal
import io.github.syrou.reaktiv.navigation.definition.Navigatable
import io.github.syrou.reaktiv.navigation.definition.NavigationGraph
import io.github.syrou.reaktiv.navigation.definition.StartDestination
import io.github.syrou.reaktiv.navigation.link.LinkAccess
import io.github.syrou.reaktiv.navigation.link.LinkAlias
import io.github.syrou.reaktiv.navigation.link.LinkGraph
import io.github.syrou.reaktiv.navigation.link.LinkKind
import io.github.syrou.reaktiv.navigation.link.LinkRoute
import io.github.syrou.reaktiv.navigation.link.LinkStart
import io.github.syrou.reaktiv.navigation.link.NavigationLinkMap
import io.github.syrou.reaktiv.navigation.model.InterceptDefinition
import io.github.syrou.reaktiv.navigation.model.NavigationGuard

private const val SAMPLE_VALUE = "reaktiv-sample-1"

internal fun buildLinkMap(data: PrecomputedNavigationData, webPrefix: String?): NavigationLinkMap {
    val guardIds = GuardIds()
    val graphs = data.graphDefinitions.values.map { graph -> linkGraph(graph, data, guardIds) }
    val routes = mutableListOf<LinkRoute>()
    val listed = mutableSetOf<String>()
    data.graphDefinitions.values.forEach { graph ->
        graph.navigatables.forEach { navigatable ->
            val path = pathIn(graph.route, navigatable, data)
            if (listed.add(path)) routes += linkRoute(path, graph.route, navigatable, data, guardIds)
        }
    }
    listOfNotNull(data.notFoundScreen, data.crashScreen, data.loadingModal).forEach { navigatable ->
        val path = navigatable.route
        if (data.routeToNavigatable[path] === navigatable && listed.add(path)) {
            routes += linkRoute(path, ROOT_GRAPH, navigatable, data, guardIds)
        }
    }
    val aliases = data.deepLinkAliases.map { alias ->
        LinkAlias(alias.pattern, alias.targetRoute, alias.template.paramNames)
    }
    return NavigationLinkMap(graphs = graphs, routes = routes, aliases = aliases, webPrefix = webPrefix)
}

private class GuardIds {
    private val seen = mutableListOf<NavigationGuard>()

    fun of(intercept: InterceptDefinition?): List<Int> {
        if (intercept == null) return emptyList()
        val chain = intercept.outerGuards.map { it.guard } + intercept.guard
        return chain.map { guard ->
            val index = seen.indexOfFirst { it === guard }
            if (index >= 0) {
                index + 1
            } else {
                seen += guard
                seen.size
            }
        }
    }
}

private fun pathIn(graphId: String, navigatable: Navigatable, data: PrecomputedNavigationData): String {
    val graphPath = data.graphIndex.path(graphId)
    return if (graphPath.isNullOrEmpty()) navigatable.route else "$graphPath/${navigatable.route}"
}

private fun linkGraph(graph: NavigationGraph, data: PrecomputedNavigationData, guardIds: GuardIds): LinkGraph {
    val id = graph.route
    val parent = data.graphIndex.parent(id)
    val start = when {
        data.graphEntries[id]?.route != null -> LinkStart.Dynamic
        else -> when (val destination = graph.startDestination) {
            is StartDestination.DirectScreen -> LinkStart.Route(pathIn(id, destination.screen, data))
            is StartDestination.GraphReference -> LinkStart.Graph(destination.graphId)
            null -> LinkStart.None
        }
    }
    val access = if (id == ROOT_GRAPH || start == LinkStart.None) LinkAccess.Internal else LinkAccess.Linkable
    return LinkGraph(
        id = id,
        path = data.graphIndex.path(id).orEmpty(),
        parent = parent,
        start = start,
        guards = guardIds.of(data.interceptsByGraphId[id]),
        access = access
    )
}

private fun linkRoute(
    path: String,
    graphId: String,
    navigatable: Navigatable,
    data: PrecomputedNavigationData,
    guardIds: GuardIds
): LinkRoute {
    val template = RouteTemplate.parse(path)
    val access = when {
        !data.isAddressable(navigatable) -> LinkAccess.Internal
        navigatable == data.notFoundScreen -> LinkAccess.Fallback
        !resolvesTo(template, navigatable, data) -> LinkAccess.Shadowed
        else -> LinkAccess.Linkable
    }
    return LinkRoute(
        path = path,
        route = navigatable.route,
        graph = graphId,
        screen = navigatable::class.simpleName,
        kind = if (navigatable is Modal) LinkKind.Modal else LinkKind.Screen,
        params = template.paramNames,
        guards = guardIds.of(data.interceptsByPath[path]),
        access = access
    )
}

private fun resolvesTo(template: RouteTemplate, navigatable: Navigatable, data: PrecomputedNavigationData): Boolean {
    val sample = (template.fill { SAMPLE_VALUE } as? RouteTemplate.Fill.Filled)?.location ?: return false
    return data.routeResolver.resolve(sample)?.targetNavigatable === navigatable
}
