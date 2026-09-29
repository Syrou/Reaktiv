package io.github.syrou.reaktiv.devtools.ui.components

import io.github.syrou.reaktiv.devtools.ui.ViewChoices
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.syrou.reaktiv.core.util.currentTimeMillis
import io.github.syrou.reaktiv.devtools.protocol.NavigationSnapshot
import io.github.syrou.reaktiv.devtools.ui.DevToolsColors
import io.github.syrou.reaktiv.devtools.ui.LocalSyntaxColors
import io.github.syrou.reaktiv.devtools.ui.navmap.MapAccess
import io.github.syrou.reaktiv.devtools.ui.navmap.MapCamera
import io.github.syrou.reaktiv.devtools.ui.navmap.MapCard
import io.github.syrou.reaktiv.devtools.ui.navmap.MapHit
import io.github.syrou.reaktiv.devtools.ui.navmap.MapKind
import io.github.syrou.reaktiv.devtools.ui.navmap.MapLayout
import io.github.syrou.reaktiv.devtools.ui.navmap.MapModel
import io.github.syrou.reaktiv.devtools.ui.navmap.MapRect
import io.github.syrou.reaktiv.devtools.ui.navmap.MapRoute
import io.github.syrou.reaktiv.devtools.ui.navmap.MapSearch
import io.github.syrou.reaktiv.devtools.ui.navmap.MapStart
import io.github.syrou.reaktiv.devtools.ui.navmap.aliasesTo
import io.github.syrou.reaktiv.devtools.ui.navmap.centerOn
import io.github.syrou.reaktiv.devtools.ui.navmap.fitCamera
import io.github.syrou.reaktiv.devtools.ui.navmap.graphChain
import io.github.syrou.reaktiv.devtools.ui.navmap.hitTest
import io.github.syrou.reaktiv.devtools.ui.navmap.route
import io.github.syrou.reaktiv.devtools.ui.navmap.zoomAt
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private const val FULL_DETAIL_SCALE = 0.45f
private const val BAR_DETAIL_SCALE = 0.2f
private const val MAP_DOUBLE_CLICK_MS = 300L

private class MapPalette(
    val card: Color,
    val header: Color,
    val border: Color,
    val text: Color,
    val muted: Color,
    val primary: Color,
    val start: Color,
    val modal: Color,
    val guard: List<Color>,
    val here: Color,
    val match: Color,
    val param: Color,
    val warning: Color,
    val edge: Color
)

@Composable
internal fun NavigationMap(
    view: ViewChoices = ViewChoices(),
    onViewChange: (ViewChoices) -> Unit = {},
    model: MapModel,
    summary: String,
    layoutKey: Any,
    layout: MapLayout,
    search: MapSearch,
    snapshot: NavigationSnapshot?,
    selectedRoute: String?,
    selectedGraph: String?,
    onSelectRoute: (String) -> Unit,
    onSelectGraph: (String) -> Unit,
    onToggleGraph: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    val palette = MapPalette(
        card = colors.surface,
        header = colors.surfaceVariant,
        border = colors.outline,
        text = colors.onSurface,
        muted = colors.onSurfaceVariant,
        primary = colors.primary,
        start = colors.primary,
        modal = colors.secondary,
        guard = listOf(colors.tertiary, DevToolsColors.warning, colors.secondary, DevToolsColors.success, colors.primary),
        here = DevToolsColors.success,
        match = DevToolsColors.warning,
        param = LocalSyntaxColors.current.number,
        warning = DevToolsColors.warning,
        edge = colors.outline
    )
    val textMeasurer = rememberTextMeasurer()
    val focusRequester = remember { FocusRequester() }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    var camera by remember { mutableStateOf<MapCamera?>(null) }
    var fittedFor by remember { mutableStateOf<Any?>(null) }
    var hovered by remember { mutableStateOf<MapHit?>(null) }
    var showLegend by storeBacked(view.mapLegendShown) { onViewChange(view.copy(mapLegendShown = it)) }

    val viewWidth = viewSize.width.toFloat()
    val viewHeight = viewSize.height.toFloat()
    LaunchedEffect(viewSize, layoutKey) {
        if (viewSize != IntSize.Zero && (camera == null || fittedFor != layoutKey)) {
            camera = fitCamera(layout.bounds, viewSize.width.toFloat(), viewSize.height.toFloat(), 32f)
            fittedFor = layoutKey
        }
    }
    val liveChain = remember(model, snapshot?.currentPath) {
        val current = snapshot?.currentPath?.let(model::route)
        current?.let { model.graphChain(it.graph).map { graph -> graph.id }.toSet() }.orEmpty()
    }
    val backStackOrder = remember(snapshot) {
        snapshot?.backStack?.mapIndexed { index, entry -> entry.path to index + 1 }?.toMap().orEmpty()
    }
    val deviceParams = remember(snapshot) {
        snapshot?.backStack?.associate { entry -> entry.path to entry.params }.orEmpty()
    }

    val latest = rememberUpdatedState(layout)
    val latestCamera = rememberUpdatedState(camera)
    val latestSelectRoute = rememberUpdatedState(onSelectRoute)
    val latestSelectGraph = rememberUpdatedState(onSelectGraph)
    val latestToggle = rememberUpdatedState(onToggleGraph)

    fun fit() {
        camera = fitCamera(layout.bounds, viewWidth, viewHeight, 32f)
    }

    fun zoomCentered(factor: Float) {
        camera = camera?.zoomAt(factor, viewWidth / 2f, viewHeight / 2f)
    }

    Column(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clipToBounds()
                .onSizeChanged { viewSize = it }
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .focusRequester(focusRequester)
                    .focusable()
                    .onKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                        val current = camera ?: return@onKeyEvent false
                        when (event.key) {
                            Key.W, Key.Equals, Key.Plus -> zoomCentered(1.25f)
                            Key.S, Key.Minus -> zoomCentered(0.8f)
                            Key.A, Key.DirectionLeft -> camera = current.copy(offsetX = current.offsetX + viewWidth / 8f)
                            Key.D, Key.DirectionRight -> camera = current.copy(offsetX = current.offsetX - viewWidth / 8f)
                            Key.DirectionUp -> camera = current.copy(offsetY = current.offsetY + viewHeight / 8f)
                            Key.DirectionDown -> camera = current.copy(offsetY = current.offsetY - viewHeight / 8f)
                            Key.F -> fit()
                            else -> return@onKeyEvent false
                        }
                        true
                    }
                    .pointerInput(Unit) {
                        var pressPosition: Offset? = null
                        var dragging = false
                        var lastClickAt = 0L
                        var lastClickTarget: MapHit? = null
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull() ?: continue
                                val cam = latestCamera.value ?: continue
                                when (event.type) {
                                    PointerEventType.Scroll -> {
                                        val delta = change.scrollDelta.y
                                        if (delta != 0f) {
                                            camera = cam.zoomAt(if (delta > 0f) 0.85f else 1.18f, change.position.x, change.position.y)
                                            change.consume()
                                        }
                                    }
                                    PointerEventType.Move -> {
                                        val pressed = pressPosition
                                        if (pressed != null) {
                                            val moved = change.position - pressed
                                            if (!dragging && (abs(moved.x) > viewConfiguration.touchSlop || abs(moved.y) > viewConfiguration.touchSlop)) {
                                                dragging = true
                                            }
                                            if (dragging) {
                                                val step = change.position - change.previousPosition
                                                camera = cam.copy(offsetX = cam.offsetX + step.x, offsetY = cam.offsetY + step.y)
                                                change.consume()
                                            }
                                        } else {
                                            hovered = latest.value.hitTest(cam.toWorldX(change.position.x), cam.toWorldY(change.position.y))
                                        }
                                    }
                                    PointerEventType.Exit -> hovered = null
                                    PointerEventType.Press -> {
                                        pressPosition = change.position
                                        dragging = false
                                        focusRequester.requestFocus()
                                    }
                                    PointerEventType.Release -> {
                                        val pressed = pressPosition
                                        pressPosition = null
                                        if (pressed == null || dragging) continue
                                        val hit = latest.value.hitTest(cam.toWorldX(change.position.x), cam.toWorldY(change.position.y))
                                        val now = currentTimeMillis()
                                        val doubleClick = now - lastClickAt < MAP_DOUBLE_CLICK_MS && hit == lastClickTarget
                                        lastClickAt = now
                                        lastClickTarget = hit
                                        when (hit) {
                                            is MapHit.Toggle -> latestToggle.value(hit.id)
                                            is MapHit.Route -> latestSelectRoute.value(hit.path)
                                            is MapHit.Graph -> if (doubleClick) latestToggle.value(hit.id) else latestSelectGraph.value(hit.id)
                                            null -> Unit
                                        }
                                    }
                                    else -> Unit
                                }
                            }
                        }
                    }
            ) {
                val cam = camera ?: return@Canvas
                val visible = MapRect(cam.toWorldX(0f), cam.toWorldY(0f), cam.toWorldX(size.width), cam.toWorldY(size.height))
                drawBands(layout, cam, visible, palette, textMeasurer)
                drawEdges(layout, cam, liveChain, palette)
                layout.cards.forEach { card ->
                    if (!card.frame.intersects(visible)) return@forEach
                    drawCard(
                        card = card,
                        cam = cam,
                        model = model,
                        palette = palette,
                        textMeasurer = textMeasurer,
                        search = search,
                        selectedRoute = selectedRoute,
                        selectedGraph = selectedGraph,
                        hovered = hovered,
                        currentPath = snapshot?.currentPath,
                        backStackOrder = backStackOrder,
                        deviceParams = deviceParams,
                        inLiveChain = card.graph.id in liveChain
                    )
                }
                if (cam.scale < BAR_DETAIL_SCALE) drawOverviewLabels(layout, cam, palette, textMeasurer, liveChain)
            }

            Column(
                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
                horizontalAlignment = Alignment.End
            ) {
                Surface(shape = RoundedCornerShape(6.dp), color = colors.surfaceVariant.copy(alpha = 0.92f)) {
                    Row {
                        MapToolButton(Icons.Default.ZoomIn, "Zoom in (W)") { zoomCentered(1.25f) }
                        MapToolButton(Icons.Default.ZoomOut, "Zoom out (S)") { zoomCentered(0.8f) }
                        MapToolButton(Icons.Default.FitScreen, "Fit the whole map (F)") { fit() }
                        MapToolButton(Icons.Default.Palette, "Legend") { showLegend = !showLegend }
                    }
                }
                if (showLegend) MapLegend(palette, modifier = Modifier.padding(top = 6.dp))
            }

            val cam = camera
            if (cam != null && viewSize != IntSize.Zero) {
                MiniMap(
                    layout = layout,
                    camera = cam,
                    viewWidth = viewWidth,
                    viewHeight = viewHeight,
                    palette = palette,
                    liveChain = liveChain,
                    onCenter = { worldX, worldY ->
                        camera = cam.centerOn(MapRect(worldX, worldY, worldX, worldY), viewWidth, viewHeight)
                    },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp)
                )
            }
        }
        MapStatusLine(model, hovered, summary, cam = camera)
    }
}

@Composable
private fun MapToolButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(30.dp)) {
        Icon(icon, contentDescription = description, modifier = Modifier.size(17.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun MapStatusLine(model: MapModel, hovered: MapHit?, summary: String, cam: MapCamera?) {
    val text = when (hovered) {
        is MapHit.Route -> model.route(hovered.path)?.let { route ->
            buildString {
                append(route.path)
                route.screen?.let { append("   opens ").append(it) }
                if (route.params.isNotEmpty()) append("   params ").append(route.params.joinToString())
                if (route.guards.isNotEmpty()) append("   guarded")
            }
        } ?: hovered.path
        is MapHit.Graph -> "Graph ${hovered.id}   click to inspect, double click to fold"
        is MapHit.Toggle -> "Fold or unfold ${hovered.id}"
        null -> "Wheel or W/S zooms, drag or arrow keys pan, F fits. Click a route to inspect and open it on the device."
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(22.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text(
            listOfNotNull(summary, cam?.let { "${(it.scale * 100).toInt()}%" }).joinToString("   "),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.padding(start = 12.dp)
        )
    }
}

@Composable
private fun MapLegend(palette: MapPalette, modifier: Modifier = Modifier) {
    Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.95f), modifier = modifier.width(220.dp)) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            LegendRow(palette.start, "Start of its graph")
            LegendRow(palette.modal, "Modal")
            LegendRow(palette.guard.first(), "Guarded zone, one band per guard")
            LegendRow(palette.here, "Where the app is now")
            LegendRow(palette.match, "Search match")
            LegendRow(palette.param, "{param} to fill in")
            LegendRow(palette.muted, "Internal, not linkable")
            LegendRow(palette.warning, "Shadowed by another route")
        }
    }
}

@Composable
private fun LegendRow(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(modifier = Modifier.size(10.dp).background(color, RoundedCornerShape(2.dp)))
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun MiniMap(
    layout: MapLayout,
    camera: MapCamera,
    viewWidth: Float,
    viewHeight: Float,
    palette: MapPalette,
    liveChain: Set<String>,
    onCenter: (Float, Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val bounds = layout.bounds
    if (bounds.width <= 0f || bounds.height <= 0f) return
    val latestLayout = rememberUpdatedState(layout)
    Canvas(
        modifier = modifier
            .size(180.dp, 110.dp)
            .clipToBounds()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f), RoundedCornerShape(6.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(6.dp))
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type != PointerEventType.Press) continue
                        val change = event.changes.firstOrNull() ?: continue
                        val b = latestLayout.value.bounds
                        val scale = min((size.width - 12f) / b.width, (size.height - 12f) / b.height)
                        val originX = (size.width - b.width * scale) / 2f
                        val originY = (size.height - b.height * scale) / 2f
                        onCenter(b.left + (change.position.x - originX) / scale, b.top + (change.position.y - originY) / scale)
                        change.consume()
                    }
                }
            }
    ) {
        val scale = min((size.width - 12f) / bounds.width, (size.height - 12f) / bounds.height)
        val originX = (size.width - bounds.width * scale) / 2f
        val originY = (size.height - bounds.height * scale) / 2f
        fun mapX(x: Float) = originX + (x - bounds.left) * scale
        fun mapY(y: Float) = originY + (y - bounds.top) * scale
        layout.cards.forEach { card ->
            val color = if (card.graph.id in liveChain) palette.here else palette.muted.copy(alpha = 0.6f)
            drawRect(color, Offset(mapX(card.frame.left), mapY(card.frame.top)), Size(max(1f, card.frame.width * scale), max(1f, card.frame.height * scale)))
        }
        val left = mapX(camera.toWorldX(0f))
        val top = mapY(camera.toWorldY(0f))
        val right = mapX(camera.toWorldX(viewWidth))
        val bottom = mapY(camera.toWorldY(viewHeight))
        drawRect(palette.primary, Offset(left, top), Size(right - left, bottom - top), style = Stroke(1.5f))
    }
}

private fun MapRect.toScreen(cam: MapCamera): Rect =
    Rect(cam.toScreenX(left), cam.toScreenY(top), cam.toScreenX(right), cam.toScreenY(bottom))

private fun DrawScope.drawBands(layout: MapLayout, cam: MapCamera, visible: MapRect, palette: MapPalette, textMeasurer: TextMeasurer) {
    layout.bands.forEach { band ->
        if (!band.rect.intersects(visible)) return@forEach
        val rect = band.rect.toScreen(cam)
        val color = palette.guard[(band.guard - 1).coerceAtLeast(0) % palette.guard.size]
        val corner = CornerRadius(10f * cam.scale.coerceAtLeast(0.3f))
        drawRoundRect(color.copy(alpha = 0.06f), rect.topLeft, rect.size, corner)
        drawRoundRect(
            color.copy(alpha = 0.5f), rect.topLeft, rect.size, corner,
            style = Stroke(width = 1.2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 5f)))
        )
        if (cam.scale >= BAR_DETAIL_SCALE) {
            drawText(
                textMeasurer = textMeasurer,
                text = "guard ${band.guard}",
                topLeft = Offset(rect.left + 6f, rect.top + 2f),
                style = TextStyle(fontSize = (9f * cam.scale.coerceIn(0.6f, 1.3f)).sp, color = color.copy(alpha = 0.9f)),
                maxLines = 1
            )
        }
    }
}

private fun DrawScope.drawEdges(layout: MapLayout, cam: MapCamera, liveChain: Set<String>, palette: MapPalette) {
    layout.edges.forEach { edge ->
        val live = edge.from in liveChain && edge.to in liveChain
        val color = when {
            live -> palette.here
            edge.toStart -> palette.start.copy(alpha = 0.8f)
            else -> palette.edge
        }
        val path = Path()
        edge.points.forEachIndexed { index, (x, y) ->
            val sx = cam.toScreenX(x)
            val sy = cam.toScreenY(y)
            if (index == 0) path.moveTo(sx, sy) else path.lineTo(sx, sy)
        }
        drawPath(path, color, style = Stroke(width = if (live) 2.5f else 1.3f))
        val (endX, endY) = edge.points.last()
        drawCircle(color, radius = if (live) 3.5f else 2.5f, center = Offset(cam.toScreenX(endX), cam.toScreenY(endY)))
    }
}

private fun routeLabel(route: MapRoute, palette: MapPalette, values: Map<String, String>): AnnotatedString = buildAnnotatedString {
    var index = 0
    val text = route.route
    while (index < text.length) {
        val open = text.indexOf('{', index)
        if (open < 0) {
            append(text.substring(index))
            break
        }
        append(text.substring(index, open))
        val close = text.indexOf('}', open).takeIf { it > open } ?: text.lastIndex
        val placeholder = text.substring(open, close + 1)
        val value = values[placeholder.removePrefix("{").removeSuffix("}")]
        withStyle(SpanStyle(color = palette.param, fontWeight = if (value == null) null else FontWeight.SemiBold)) {
            append(value ?: placeholder)
        }
        index = close + 1
    }
}

private fun DrawScope.drawCard(
    card: MapCard,
    cam: MapCamera,
    model: MapModel,
    palette: MapPalette,
    textMeasurer: TextMeasurer,
    search: MapSearch,
    selectedRoute: String?,
    selectedGraph: String?,
    hovered: MapHit?,
    currentPath: String?,
    backStackOrder: Map<String, Int>,
    deviceParams: Map<String, Map<String, String>>,
    inLiveChain: Boolean
) {
    val frame = card.frame.toScreen(cam)
    val header = card.header.toScreen(cam)
    val corner = CornerRadius(7f * cam.scale.coerceAtLeast(0.3f))
    val graph = card.graph
    val selected = graph.id == selectedGraph
    val dimmed = search.isActive && graph.id !in search.graphs && card.rows.none { it.route.path in search.routes }
    val alpha = if (dimmed) 0.45f else 1f

    drawRoundRect(palette.card.copy(alpha = alpha), frame.topLeft, frame.size, corner)
    drawRoundRect(palette.header.copy(alpha = alpha), header.topLeft, header.size, corner)
    val borderColor = when {
        selected -> palette.primary
        inLiveChain -> palette.here
        graph.id in search.graphs -> palette.match
        hovered == MapHit.Graph(graph.id) -> palette.text.copy(alpha = 0.6f)
        else -> palette.border
    }
    drawRoundRect(borderColor, frame.topLeft, frame.size, corner, style = Stroke(width = if (selected || inLiveChain) 2.2f else 1f))
    if (graph.guards.isNotEmpty()) {
        val color = palette.guard[(graph.guards.last() - 1).coerceAtLeast(0) % palette.guard.size]
        drawRect(color, header.topLeft, Size(4f * cam.scale.coerceAtLeast(0.5f), header.height))
    }

    if (cam.scale < BAR_DETAIL_SCALE) {
        card.rows.forEach { row ->
            if (row.route.path == currentPath) drawRect(palette.here, row.rect.toScreen(cam).topLeft, row.rect.toScreen(cam).size)
        }
        return
    }

    val pad = 10f * cam.scale
    val title = if (graph.id == "root") "App" else graph.id
    val badge = when (graph.start) {
        is MapStart.Dynamic -> "  start decided at runtime"
        is MapStart.None -> if (graph.id == "root") "" else "  no start"
        else -> ""
    }
    drawText(
        textMeasurer = textMeasurer,
        text = buildAnnotatedString {
            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = palette.text.copy(alpha = alpha))) { append(title) }
            withStyle(SpanStyle(color = palette.modal.copy(alpha = alpha), fontSize = (10f * cam.scale).sp)) { append(badge) }
        },
        topLeft = Offset(header.left + pad, header.top + 4f * cam.scale),
        style = TextStyle(fontSize = (13f * cam.scale).sp),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        size = Size(max(1f, header.width - 2 * pad - header.height), max(1f, header.height / 2f))
    )
    drawText(
        textMeasurer = textMeasurer,
        text = if (graph.path.isEmpty()) "/" else "/${graph.path}",
        topLeft = Offset(header.left + pad, header.top + header.height * 0.5f),
        style = TextStyle(fontSize = (10f * cam.scale).sp, fontFamily = FontFamily.Monospace, color = palette.muted.copy(alpha = alpha)),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        size = Size(max(1f, header.width - 2 * pad - header.height), max(1f, header.height / 2f))
    )
    card.toggle?.let { toggleRect ->
        val t = toggleRect.toScreen(cam)
        val cx = t.center.x
        val cy = t.center.y
        val s = 5f * cam.scale.coerceIn(0.5f, 1.5f)
        val arrow = Path().apply {
            if (card.collapsed) {
                moveTo(cx - s * 0.6f, cy - s)
                lineTo(cx + s * 0.8f, cy)
                lineTo(cx - s * 0.6f, cy + s)
            } else {
                moveTo(cx - s, cy - s * 0.6f)
                lineTo(cx + s, cy - s * 0.6f)
                lineTo(cx, cy + s * 0.8f)
            }
            close()
        }
        drawPath(arrow, if (hovered == MapHit.Toggle(graph.id)) palette.text else palette.muted)
    }

    if (card.collapsed) {
        val summary = listOfNotNull(
            card.hiddenRoutes.takeIf { it > 0 }?.let { if (it == 1) "1 route" else "$it routes" },
            card.hiddenGraphs.takeIf { it > 0 }?.let { if (it == 1) "1 graph" else "$it graphs" }
        ).joinToString(", ") + " folded"
        drawText(
            textMeasurer = textMeasurer,
            text = summary,
            topLeft = Offset(frame.left + pad, header.bottom + 3f * cam.scale),
            style = TextStyle(fontSize = (11f * cam.scale).sp, color = palette.muted),
            maxLines = 1
        )
        return
    }

    card.rowZones.forEach { zone ->
        val z = zone.rect.toScreen(cam)
        val color = palette.guard[(zone.guards.last() - 1).coerceAtLeast(0) % palette.guard.size]
        drawRect(color.copy(alpha = 0.12f), z.topLeft, z.size)
        drawRect(color.copy(alpha = 0.7f), z.topLeft, Size(2.5f, z.height))
    }

    card.rows.forEach { row ->
        val rect = row.rect.toScreen(cam)
        val route = row.route
        val isCurrent = route.path == currentPath
        val isMatch = route.path in search.routes
        val rowAlpha = if (search.isActive && !isMatch) 0.4f else 1f
        when {
            route.path == selectedRoute -> drawRect(palette.primary.copy(alpha = 0.28f), rect.topLeft, rect.size)
            isCurrent -> drawRect(palette.here.copy(alpha = 0.2f), rect.topLeft, rect.size)
            isMatch -> drawRect(palette.match.copy(alpha = 0.18f), rect.topLeft, rect.size)
            hovered == MapHit.Route(route.path) -> drawRect(palette.text.copy(alpha = 0.07f), rect.topLeft, rect.size)
        }
        if (isCurrent) drawRect(palette.here, rect.topLeft, Size(3f, rect.height))

        if (cam.scale < FULL_DETAIL_SCALE) {
            val barColor = when {
                route.kind == MapKind.Modal -> palette.modal
                row.isStart -> palette.start
                else -> palette.muted
            }
            drawRoundRect(
                barColor.copy(alpha = 0.55f * rowAlpha),
                Offset(rect.left + pad, rect.top + rect.height * 0.3f),
                Size(max(2f, (rect.width - 2 * pad) * min(1f, route.route.length / 28f)), rect.height * 0.4f),
                CornerRadius(2f)
            )
            return@forEach
        }

        val glyphX = rect.left + pad + 3f * cam.scale
        val glyphY = rect.center.y
        val g = 3.5f * cam.scale
        when {
            row.isStart -> drawPath(Path().apply {
                moveTo(glyphX - g, glyphY - g * 1.2f)
                lineTo(glyphX + g * 1.2f, glyphY)
                lineTo(glyphX - g, glyphY + g * 1.2f)
                close()
            }, palette.start.copy(alpha = rowAlpha))
            route.kind == MapKind.Modal -> drawPath(Path().apply {
                moveTo(glyphX, glyphY - g * 1.3f)
                lineTo(glyphX + g * 1.3f, glyphY)
                lineTo(glyphX, glyphY + g * 1.3f)
                lineTo(glyphX - g * 1.3f, glyphY)
                close()
            }, palette.modal.copy(alpha = rowAlpha))
            else -> drawCircle(palette.muted.copy(alpha = rowAlpha), radius = g * 0.8f, center = Offset(glyphX, glyphY))
        }

        val linkable = route.access == MapAccess.Linkable
        val textColor = when (route.access) {
            MapAccess.Linkable -> palette.text
            MapAccess.Fallback -> palette.text.copy(alpha = 0.8f)
            MapAccess.Internal -> palette.muted.copy(alpha = 0.7f)
            MapAccess.Shadowed -> palette.warning.copy(alpha = 0.8f)
        }
        val tags = buildList {
            backStackOrder[route.path]?.let { add("#$it") }
            if (isCurrent) add("here")
            when (route.access) {
                MapAccess.Fallback -> add("404")
                MapAccess.Internal -> add("internal")
                MapAccess.Shadowed -> add("shadowed")
                MapAccess.Linkable -> Unit
            }
            model.aliasesTo(route.path).size.takeIf { it > 0 }?.let { add(if (it == 1) "alias" else "$it aliases") }
        }.joinToString("  ")
        val tagWidth = if (tags.isEmpty()) 0f else textMeasurer.measure(tags, TextStyle(fontSize = (9f * cam.scale).sp)).size.width.toFloat() + pad
        val labelLeft = glyphX + 9f * cam.scale
        drawText(
            textMeasurer = textMeasurer,
            text = routeLabel(route, palette, deviceParams[route.path].orEmpty()),
            topLeft = Offset(labelLeft, rect.top + rect.height * 0.18f),
            style = TextStyle(
                fontSize = (12f * cam.scale).sp,
                fontFamily = FontFamily.Monospace,
                color = textColor.copy(alpha = textColor.alpha * rowAlpha),
                textDecoration = if (route.access == MapAccess.Shadowed) TextDecoration.LineThrough else TextDecoration.None
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            size = Size(max(1f, rect.right - labelLeft - tagWidth - pad), max(1f, rect.height))
        )
        if (tags.isNotEmpty()) {
            drawText(
                textMeasurer = textMeasurer,
                text = tags,
                topLeft = Offset(rect.right - tagWidth, rect.top + rect.height * 0.25f),
                style = TextStyle(fontSize = (9f * cam.scale).sp, color = (if (isCurrent) palette.here else palette.muted).copy(alpha = rowAlpha)),
                maxLines = 1
            )
        }
        if (!linkable && route.access == MapAccess.Internal) {
            drawLine(
                palette.muted.copy(alpha = 0.35f),
                Offset(rect.left + pad, rect.bottom - 1f),
                Offset(rect.right - pad, rect.bottom - 1f),
                strokeWidth = 1f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f, 3f))
            )
        }
    }
}

private fun DrawScope.drawOverviewLabels(
    layout: MapLayout,
    cam: MapCamera,
    palette: MapPalette,
    textMeasurer: TextMeasurer,
    liveChain: Set<String>
) {
    layout.cards.forEach { card ->
        val frame = card.frame.toScreen(cam)
        val label = if (card.graph.id == "root") "App" else card.graph.id
        val measured = textMeasurer.measure(label, TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold))
        val x = frame.center.x - measured.size.width / 2f
        val y = frame.top - measured.size.height - 2f
        drawText(
            textLayoutResult = measured,
            color = if (card.graph.id in liveChain) palette.here else palette.text,
            topLeft = Offset(x, y)
        )
    }
}
