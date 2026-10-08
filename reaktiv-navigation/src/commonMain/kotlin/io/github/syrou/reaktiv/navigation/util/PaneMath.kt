package io.github.syrou.reaktiv.navigation.util

import io.github.syrou.reaktiv.navigation.definition.Modal
import io.github.syrou.reaktiv.navigation.definition.NavigationGraph
import io.github.syrou.reaktiv.navigation.definition.PaneBlock
import io.github.syrou.reaktiv.navigation.definition.PaneLayout
import io.github.syrou.reaktiv.navigation.definition.WindowWidthClass
import io.github.syrou.reaktiv.navigation.layer.RenderLayer
import io.github.syrou.reaktiv.navigation.model.NavigationEntry

internal class PaneSurface(
    val graphId: String,
    val block: PaneBlock,
    val columns: List<NavigationEntry?>
) {
    fun places(entry: NavigationEntry): Boolean = columns.any { it?.stableKey == entry.stableKey }

    val entries: List<NavigationEntry> get() = columns.filterNotNull()
}

internal object PaneMath {

    fun layoutOf(graphId: String, graphs: Map<String, NavigationGraph>): PaneLayout? =
        graphs[graphId]?.declaration?.paneLayout

    fun graphOf(entry: NavigationEntry, graphs: Map<String, NavigationGraph>): String? =
        entry.graphChain.lastOrNull { layoutOf(it, graphs) != null }

    fun activeBlock(
        graphId: String,
        graphs: Map<String, NavigationGraph>,
        widthClass: WindowWidthClass?
    ): PaneBlock? = layoutOf(graphId, graphs)?.blockFor(widthClass)

    fun columnOf(
        entry: NavigationEntry,
        graphs: Map<String, NavigationGraph>,
        widthClass: WindowWidthClass?
    ): Int? {
        val graphId = graphOf(entry, graphs) ?: return null
        val block = activeBlock(graphId, graphs, widthClass) ?: return null
        return block.columnOf(entry.navigatable).takeIf { it >= 0 }
    }

    fun surface(
        content: List<NavigationEntry>,
        widthClass: WindowWidthClass?,
        graphs: Map<String, NavigationGraph>
    ): PaneSurface? {
        if (widthClass == null) return null
        for (index in content.indices.reversed()) {
            val entry = content[index]
            val graphId = graphOf(entry, graphs)
            val block = graphId?.let { activeBlock(it, graphs, widthClass) }
            if (graphId != null && block != null && block.columnOf(entry.navigatable) >= 0) {
                return PaneSurface(graphId, block, columnsAt(content, index, graphId, block))
            }
            if (entry.navigatable !is Modal) return null
        }
        return null
    }

    private fun columnsAt(
        content: List<NavigationEntry>,
        anchor: Int,
        graphId: String,
        block: PaneBlock
    ): List<NavigationEntry?> {
        val run = runIndices(content, anchor, graphId)
        var floor = -1
        return block.columns.map { column ->
            val index = run.lastOrNull { content[it].navigatable in column.navigatables }
            if (index == null || index < floor) {
                null
            } else {
                floor = index
                content[index]
            }
        }
    }

    private fun runIndices(stack: List<NavigationEntry>, from: Int, graphId: String): List<Int> {
        val run = mutableListOf<Int>()
        for (index in from downTo 0) {
            val entry = stack[index]
            when {
                entry.navigatable.renderLayer == RenderLayer.SYSTEM -> Unit
                graphId in entry.graphChain -> run.add(index)
                entry.navigatable is Modal -> Unit
                else -> break
            }
        }
        return run.asReversed()
    }

    fun reopenIndex(
        stack: List<NavigationEntry>,
        target: NavigationEntry,
        graphs: Map<String, NavigationGraph>
    ): Int {
        val graphId = graphOf(target, graphs) ?: return -1
        val layout = layoutOf(graphId, graphs) ?: return -1
        if (target.navigatable !in layout.placed) return -1
        if (stack.isEmpty()) return -1
        return runIndices(stack, stack.lastIndex, graphId)
            .firstOrNull { stack[it].navigatable == target.navigatable } ?: -1
    }
}
