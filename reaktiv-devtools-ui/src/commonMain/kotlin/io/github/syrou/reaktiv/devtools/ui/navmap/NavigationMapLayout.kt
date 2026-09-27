package io.github.syrou.reaktiv.devtools.ui.navmap

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

internal data class MapRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerY: Float get() = (top + bottom) / 2f

    fun contains(x: Float, y: Float): Boolean = x in left..right && y in top..bottom

    fun intersects(other: MapRect): Boolean =
        left < other.right && other.left < right && top < other.bottom && other.top < bottom

    fun union(other: MapRect): MapRect =
        MapRect(min(left, other.left), min(top, other.top), max(right, other.right), max(bottom, other.bottom))
}

internal data class MapMetrics(
    val charWidth: Float = 7.2f,
    val rowHeight: Float = 22f,
    val headerHeight: Float = 40f,
    val cardPadding: Float = 10f,
    val tagWidth: Float = 56f,
    val columnGap: Float = 96f,
    val siblingGap: Float = 18f,
    val bandPadding: Float = 12f,
    val minCardWidth: Float = 160f,
    val maxCardWidth: Float = 360f,
    val maxRowsPerColumn: Int = 14
)

internal data class MapRow(val route: MapRoute, val rect: MapRect, val isStart: Boolean)

internal data class MapRowZone(val rect: MapRect, val guards: List<Int>)

internal data class MapCard(
    val graph: MapGraph,
    val depth: Int,
    val frame: MapRect,
    val header: MapRect,
    val toggle: MapRect?,
    val rows: List<MapRow>,
    val rowZones: List<MapRowZone>,
    val collapsed: Boolean,
    val hiddenRoutes: Int,
    val hiddenGraphs: Int
)

internal data class MapEdge(val from: String, val to: String, val points: List<Pair<Float, Float>>, val toStart: Boolean)

internal data class MapBand(val graphId: String, val guard: Int, val rect: MapRect)

internal data class MapLayout(
    val cards: List<MapCard>,
    val edges: List<MapEdge>,
    val bands: List<MapBand>,
    val bounds: MapRect,
    val routeRects: Map<String, MapRect>
)

internal sealed interface MapHit {
    data class Route(val path: String) : MapHit
    data class Graph(val id: String) : MapHit
    data class Toggle(val id: String) : MapHit
}

internal data class MapCamera(val scale: Float, val offsetX: Float, val offsetY: Float) {
    fun toScreenX(x: Float): Float = x * scale + offsetX
    fun toScreenY(y: Float): Float = y * scale + offsetY
    fun toWorldX(x: Float): Float = (x - offsetX) / scale
    fun toWorldY(y: Float): Float = (y - offsetY) / scale
}

internal data class MapSearch(val routes: Set<String>, val graphs: Set<String>, val expand: Set<String>) {
    val isActive: Boolean get() = routes.isNotEmpty() || graphs.isNotEmpty()
}

internal fun MapModel.search(query: String): MapSearch {
    val needle = query.trim().lowercase()
    if (needle.isEmpty()) return MapSearch(emptySet(), emptySet(), emptySet())
    val routes = this.routes.filter { route ->
        route.path.lowercase().contains(needle) || route.screen?.lowercase()?.contains(needle) == true
    }.map { it.path }.toSet()
    val graphs = this.graphs.filter { it.id.lowercase().contains(needle) || it.path.lowercase().contains(needle) }
        .map { it.id }.toSet()
    val owners = this.routes.filter { it.path in routes }.map { it.graph } + graphs
    val expand = owners.flatMap { owner -> graphChain(owner).map { it.id } }.toSet()
    return MapSearch(routes, graphs, expand)
}

internal fun layoutLinkMap(
    map: MapModel,
    collapsed: Set<String>,
    metrics: MapMetrics = MapMetrics(),
    expand: Set<String> = emptySet()
): MapLayout {
    val byId = map.graphs.associateBy { it.id }
    val children = map.graphs.filter { it.parent != null && byId.containsKey(it.parent) }.groupBy { it.parent!! }
    val routesOf = map.routes.groupBy { it.graph }
    val roots = map.graphs.filter { it.parent == null || !byId.containsKey(it.parent) }
    val effectiveCollapsed = collapsed - expand

    fun isCollapsed(id: String) = id in effectiveCollapsed && (children[id].orEmpty().isNotEmpty() || routesOf[id].orEmpty().isNotEmpty())

    fun addedGuards(graph: MapGraph): Int {
        val parentGuards = graph.parent?.let { byId[it] }?.guards.orEmpty()
        return if (graph.guards.take(parentGuards.size) == parentGuards) graph.guards.size - parentGuards.size else graph.guards.size
    }

    fun visibleChildren(id: String): List<MapGraph> = if (isCollapsed(id)) emptyList() else children[id].orEmpty()

    val depthOf = mutableMapOf<String, Int>()
    fun assignDepth(graph: MapGraph, depth: Int) {
        if (depthOf.containsKey(graph.id)) return
        depthOf[graph.id] = depth
        visibleChildren(graph.id).forEach { assignDepth(it, depth + 1) }
    }
    roots.forEach { assignDepth(it, 0) }

    fun cardSize(graph: MapGraph): Pair<Float, Float> {
        val rows = if (isCollapsed(graph.id)) 1 else routesOf[graph.id].orEmpty().size
        val columns = max(1, ceil(rows / metrics.maxRowsPerColumn.toFloat()).toInt())
        val rowsPerColumn = min(max(rows, 1), metrics.maxRowsPerColumn)
        val longestRow = routesOf[graph.id].orEmpty().maxOfOrNull { it.route.length } ?: 0
        val rowWidth = longestRow * metrics.charWidth + 2 * metrics.cardPadding + metrics.tagWidth
        val headerWidth = max(graph.id.length, graph.path.length) * metrics.charWidth + 2 * metrics.cardPadding + metrics.tagWidth
        val columnWidth = max(rowWidth, metrics.minCardWidth).coerceAtMost(metrics.maxCardWidth)
        val width = max(columnWidth * columns, headerWidth.coerceAtMost(metrics.maxCardWidth * columns))
        val height = metrics.headerHeight + rowsPerColumn * metrics.rowHeight + metrics.cardPadding
        return width to height
    }

    val sizes = map.graphs.filter { depthOf.containsKey(it.id) }.associate { it.id to cardSize(it) }
    val maxDepth = depthOf.values.maxOrNull() ?: 0
    val columnWidths = FloatArray(maxDepth + 1)
    depthOf.forEach { (id, depth) -> columnWidths[depth] = max(columnWidths[depth], sizes.getValue(id).first) }
    val maxGuards = map.graphs.maxOfOrNull { it.guards.size } ?: 0
    val gap = max(metrics.columnGap, 2 * metrics.bandPadding * maxGuards + 48f)
    val columnX = FloatArray(maxDepth + 1)
    for (depth in 1..maxDepth) columnX[depth] = columnX[depth - 1] + columnWidths[depth - 1] + gap

    val extent = mutableMapOf<String, Float>()
    val block = mutableMapOf<String, Float>()
    fun measure(graph: MapGraph): Float {
        val kids = visibleChildren(graph.id)
        kids.forEach(::measure)
        val childrenBlock = if (kids.isEmpty()) 0f else kids.sumOf { extent.getValue(it.id).toDouble() }.toFloat() +
            metrics.siblingGap * (kids.size - 1)
        block[graph.id] = childrenBlock
        val total = max(sizes.getValue(graph.id).second, childrenBlock) + 2 * metrics.bandPadding * addedGuards(graph)
        extent[graph.id] = total
        return total
    }
    roots.forEach(::measure)

    val cards = mutableListOf<MapCard>()
    val edges = mutableListOf<MapEdge>()
    val bands = mutableListOf<MapBand>()
    val routeRects = mutableMapOf<String, MapRect>()
    val graphRects = mutableMapOf<String, MapRect>()

    fun subtreeRight(graph: MapGraph): Float {
        val own = columnX[depthOf.getValue(graph.id)] + sizes.getValue(graph.id).first
        return visibleChildren(graph.id).fold(own) { acc, child -> max(acc, subtreeRight(child)) }
    }

    fun place(graph: MapGraph, top: Float) {
        val depth = depthOf.getValue(graph.id)
        val added = addedGuards(graph)
        val inset = metrics.bandPadding * added
        val innerTop = top + inset
        val innerHeight = extent.getValue(graph.id) - 2 * inset
        val (width, height) = sizes.getValue(graph.id)
        val left = columnX[depth]
        val cardTop = innerTop + (innerHeight - height) / 2f
        val frame = MapRect(left, cardTop, left + width, cardTop + height)
        val header = MapRect(left, cardTop, left + width, cardTop + metrics.headerHeight)
        val collapsedHere = isCollapsed(graph.id)
        val hasContent = children[graph.id].orEmpty().isNotEmpty() || routesOf[graph.id].orEmpty().isNotEmpty()
        val toggle = if (hasContent) {
            MapRect(left + width - metrics.headerHeight, cardTop, left + width, cardTop + metrics.headerHeight)
        } else {
            null
        }
        val routes = routesOf[graph.id].orEmpty()
        val startPath = (graph.start as? MapStart.Route)?.path
        val rows = mutableListOf<MapRow>()
        if (!collapsedHere) {
            val columns = max(1, ceil(routes.size / metrics.maxRowsPerColumn.toFloat()).toInt())
            val columnWidth = width / columns
            routes.forEachIndexed { index, route ->
                val column = index / metrics.maxRowsPerColumn
                val row = index % metrics.maxRowsPerColumn
                val rowLeft = left + column * columnWidth
                val rowTop = cardTop + metrics.headerHeight + row * metrics.rowHeight
                val rect = MapRect(rowLeft, rowTop, rowLeft + columnWidth, rowTop + metrics.rowHeight)
                rows += MapRow(route, rect, route.path == startPath)
                routeRects[route.path] = rect
            }
        }
        val zones = mutableListOf<MapRowZone>()
        var zoneStart: MapRow? = null
        var zoneEnd: MapRow? = null
        fun closeZone() {
            val first = zoneStart ?: return
            val last = zoneEnd ?: first
            zones += MapRowZone(first.rect.union(last.rect), first.route.guards)
            zoneStart = null
            zoneEnd = null
        }
        rows.forEach { row ->
            val extra = row.route.guards != graph.guards && row.route.guards.isNotEmpty()
            val sameZone = zoneStart?.let { it.route.guards == row.route.guards && it.rect.left == row.rect.left } == true
            when {
                extra && sameZone -> zoneEnd = row
                extra -> {
                    closeZone()
                    zoneStart = row
                    zoneEnd = row
                }
                else -> closeZone()
            }
        }
        closeZone()

        val hiddenRoutes = if (collapsedHere) countRoutes(graph.id, children, routesOf) else 0
        val hiddenGraphs = if (collapsedHere) countGraphs(graph.id, children) else 0
        cards += MapCard(graph, depth, frame, header, toggle, rows, zones, collapsedHere, hiddenRoutes, hiddenGraphs)
        graphRects[graph.id] = frame

        if (added > 0) {
            val right = subtreeRight(graph)
            val guards = graph.guards.takeLast(added)
            guards.forEachIndexed { level, guard ->
                val pad = metrics.bandPadding * (added - level)
                bands += MapBand(
                    graphId = graph.id,
                    guard = guard,
                    rect = MapRect(left - pad, top + metrics.bandPadding * level, right + pad, top + extent.getValue(graph.id) - metrics.bandPadding * level)
                )
            }
        }

        val kids = visibleChildren(graph.id)
        var y = innerTop + (innerHeight - block.getValue(graph.id)) / 2f
        val startGraph = (graph.start as? MapStart.Graph)?.id
        kids.forEach { child ->
            place(child, y)
            val childFrame = graphRects.getValue(child.id)
            val fromX = frame.right
            val fromY = header.centerY
            val toX = childFrame.left
            val toY = childFrame.top + metrics.headerHeight / 2f
            val midX = toX - gap / 2f
            edges += MapEdge(graph.id, child.id, listOf(fromX to fromY, midX to fromY, midX to toY, toX to toY), child.id == startGraph)
            y += extent.getValue(child.id) + metrics.siblingGap
        }
    }

    var top = 0f
    roots.forEach { root ->
        place(root, top)
        top += extent.getValue(root.id) + metrics.siblingGap
    }

    val bounds = (cards.map { it.frame } + bands.map { it.rect })
        .reduceOrNull { acc, rect -> acc.union(rect) } ?: MapRect(0f, 0f, 0f, 0f)
    return MapLayout(cards, edges, bands, bounds, routeRects)
}

private fun countRoutes(id: String, children: Map<String, List<MapGraph>>, routesOf: Map<String, List<MapRoute>>): Int =
    routesOf[id].orEmpty().size + children[id].orEmpty().sumOf { countRoutes(it.id, children, routesOf) }

private fun countGraphs(id: String, children: Map<String, List<MapGraph>>): Int =
    children[id].orEmpty().sumOf { 1 + countGraphs(it.id, children) }

internal fun MapLayout.hitTest(x: Float, y: Float): MapHit? {
    for (card in cards) {
        if (!card.frame.contains(x, y)) continue
        card.toggle?.takeIf { it.contains(x, y) }?.let { return MapHit.Toggle(card.graph.id) }
        card.rows.firstOrNull { it.rect.contains(x, y) }?.let { return MapHit.Route(it.route.path) }
        return MapHit.Graph(card.graph.id)
    }
    return null
}

internal fun fitCamera(bounds: MapRect, viewWidth: Float, viewHeight: Float, margin: Float): MapCamera {
    if (bounds.width <= 0f || bounds.height <= 0f || viewWidth <= 0f || viewHeight <= 0f) {
        return MapCamera(1f, margin, margin)
    }
    val scale = min((viewWidth - 2 * margin) / bounds.width, (viewHeight - 2 * margin) / bounds.height)
        .coerceIn(MIN_MAP_SCALE, 1.25f)
    val offsetX = (viewWidth - bounds.width * scale) / 2f - bounds.left * scale
    val offsetY = (viewHeight - bounds.height * scale) / 2f - bounds.top * scale
    return MapCamera(scale, offsetX, offsetY)
}

internal fun MapCamera.zoomAt(factor: Float, anchorX: Float, anchorY: Float): MapCamera {
    val next = (scale * factor).coerceIn(MIN_MAP_SCALE, MAX_MAP_SCALE)
    val worldX = toWorldX(anchorX)
    val worldY = toWorldY(anchorY)
    return MapCamera(next, anchorX - worldX * next, anchorY - worldY * next)
}

internal fun MapCamera.centerOn(rect: MapRect, viewWidth: Float, viewHeight: Float): MapCamera {
    val cx = (rect.left + rect.right) / 2f
    val cy = (rect.top + rect.bottom) / 2f
    return copy(offsetX = viewWidth / 2f - cx * scale, offsetY = viewHeight / 2f - cy * scale)
}

internal const val MIN_MAP_SCALE: Float = 0.05f
internal const val MAX_MAP_SCALE: Float = 3f
