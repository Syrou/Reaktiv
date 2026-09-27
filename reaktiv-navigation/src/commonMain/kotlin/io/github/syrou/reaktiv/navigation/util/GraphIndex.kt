package io.github.syrou.reaktiv.navigation.util

import io.github.syrou.reaktiv.navigation.definition.NavigationGraph

internal class GraphIndex(
    private val graphs: Map<String, NavigationGraph>,
    val chains: Map<String, List<String>>
) {
    private val paths: Map<String, String> = chains.mapValues { (_, chain) -> chain.joinToString("/") }

    private val idsByPath: Map<String, String> = paths.entries
        .filter { (id, path) -> path.isNotEmpty() && path != id }
        .associate { (id, path) -> path to id }

    fun chain(graphId: String): List<String> = chains[graphId].orEmpty()

    fun path(graphId: String): String? = paths[graphId]?.takeIf { it.isNotEmpty() }

    fun parent(graphId: String): String? = when {
        graphId == ROOT_GRAPH || graphId !in graphs -> null
        else -> chain(graphId).dropLast(1).lastOrNull() ?: ROOT_GRAPH
    }

    fun idForPath(path: String): String? {
        val clean = normalizePath(path)
        return if (graphs.containsKey(clean)) clean else idsByPath[clean]
    }

    fun layoutChain(graphId: String): List<NavigationGraph> =
        if (graphId !in graphs) emptyList() else listOfNotNull(graphs[ROOT_GRAPH]) + chain(graphId).mapNotNull { graphs[it] }

    fun layoutsAround(graphId: String): List<NavigationGraph> = layoutChain(graphId).filter { it.layout != null }

    companion object {
        fun of(graphs: Map<String, NavigationGraph>): GraphIndex {
            val parents = buildMap {
                graphs.values.forEach { graph -> graph.nestedGraphs.forEach { put(it.route, graph.route) } }
            }
            val chains = graphs.keys.associateWith { id ->
                generateSequence(id) { parents[it] }.takeWhile { it != ROOT_GRAPH }.toList().asReversed()
            }
            return GraphIndex(graphs, chains)
        }
    }
}
