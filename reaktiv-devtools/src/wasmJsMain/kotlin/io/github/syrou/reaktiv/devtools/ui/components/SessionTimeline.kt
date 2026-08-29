package io.github.syrou.reaktiv.devtools.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.syrou.reaktiv.core.util.currentTimeMillis
import io.github.syrou.reaktiv.devtools.protocol.PHASE_TRACE_CLASS
import io.github.syrou.reaktiv.devtools.protocol.DISPATCH_TRACE_CLASS
import io.github.syrou.reaktiv.devtools.ui.LogicMethodEvent
import io.github.syrou.reaktiv.devtools.ui.NetworkEventRow
import io.github.syrou.reaktiv.devtools.ui.logicCallDepths
import io.github.syrou.reaktiv.introspection.protocol.CapturedAction
import io.github.syrou.reaktiv.introspection.protocol.CrashInfo
import io.github.syrou.reaktiv.introspection.protocol.SessionMarker

private const val RULER_DP = 16f
private const val TICK_LANE_DP = 18f
private const val NETWORK_LANE_DP = 12f
private const val MARKER_LANE_DP = 12f
private const val TIMELINE_EDGE_PADDING_MS = 250L
private const val MAX_FLAME_AREA_DP = 126f
private const val PREFERRED_ROW_DP = 14f
private const val MIN_ROW_DP = 6f
private const val MAX_DEPTH = 12
private const val LABEL_MIN_WIDTH_PX = 34f
private const val MIN_WINDOW_MS = 5L
private const val DOUBLE_CLICK_MS = 300L

private class FlameSpan(
    val callId: String,
    val name: String,
    val fullName: String,
    val startMs: Long,
    val endMs: Long,
    val depth: Int,
    val selfMs: Long,
    val failed: Boolean,
    val stall: Boolean,
    val phase: Boolean,
    val dispatch: Boolean
) {
    val durationMs: Long get() = endMs - startMs
}

private class TimelineHover(
    val timeMs: Long,
    val span: FlameSpan? = null,
    val marker: SessionMarker? = null,
    val actionIndex: Int? = null,
    val network: NetworkEventRow? = null
)

private fun buildFlameSpans(events: List<LogicMethodEvent>): List<FlameSpan> {
    val startsByCallId = events
        .filterIsInstance<LogicMethodEvent.Started>()
        .associateBy { it.callId }

    val depthOf = logicCallDepths(events)

    val durations = mutableMapOf<String, Long>()
    val failures = mutableSetOf<String>()
    events.filterIsInstance<LogicMethodEvent.Completed>().forEach { durations[it.callId] = it.durationMs }
    events.filterIsInstance<LogicMethodEvent.Failed>().forEach {
        durations[it.callId] = it.durationMs
        failures.add(it.callId)
    }

    val childDurations = mutableMapOf<String, Long>()
    durations.keys.forEach { callId ->
        val parentId = startsByCallId[callId]?.event?.parentCallId ?: return@forEach
        if (parentId in durations) {
            childDurations[parentId] = (childDurations[parentId] ?: 0L) + (durations[callId] ?: 0L)
        }
    }

    return durations.mapNotNull { (callId, durationMs) ->
        val start = startsByCallId[callId] ?: return@mapNotNull null
        val shortClass = start.logicClass.substringAfterLast('.')
        FlameSpan(
            callId = callId,
            name = "$shortClass.${start.methodName}",
            fullName = "${start.logicClass}.${start.methodName}",
            startMs = start.timestamp,
            endMs = start.timestamp + durationMs,
            depth = depthOf(callId).coerceAtMost(MAX_DEPTH),
            selfMs = (durationMs - (childDurations[callId] ?: 0L)).coerceAtLeast(0L),
            failed = callId in failures,
            stall = start.logicClass == "MainThreadWatchdog",
            phase = start.logicClass == PHASE_TRACE_CLASS,
            dispatch = start.logicClass == DISPATCH_TRACE_CLASS
        )
    }
}

private fun niceTickStep(visibleMs: Long, widthPx: Float): Long {
    if (widthPx <= 0f) return visibleMs.coerceAtLeast(1L)
    val targetTicks = (widthPx / 90f).coerceAtLeast(1f)
    val rough = (visibleMs / targetTicks).toLong().coerceAtLeast(1L)
    var magnitude = 1L
    while (magnitude * 10 <= rough) magnitude *= 10
    return when {
        rough >= magnitude * 5 -> magnitude * 5
        rough >= magnitude * 2 -> magnitude * 2
        else -> magnitude
    }
}

@Composable
internal fun SessionTimeline(
    dataRevision: Long,
    actions: List<CapturedAction>,
    logicMethodEvents: List<LogicMethodEvent>,
    markers: List<SessionMarker>,
    crash: CrashInfo?,
    networkEvents: List<NetworkEventRow> = emptyList(),
    selectedActionIndex: Int?,
    selectedSpanCallId: String? = null,
    selectedNetworkRequestId: String? = null,
    pinnedTimeMs: Long? = null,
    onPinTime: (Long?) -> Unit = {},
    onSeek: (Int) -> Unit,
    onSelectSpan: (String) -> Unit = {},
    onSelectNetwork: (String) -> Unit = {},
    canDropMarker: Boolean = false,
    onDropMarker: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    if (actions.isEmpty()) return

    val spans = remember(dataRevision) { buildFlameSpans(logicMethodEvents) }
    val flameRows = remember(dataRevision) {
        ((spans.maxOfOrNull { it.depth } ?: 0) + 1).coerceAtMost(MAX_DEPTH + 1)
    }
    val rowDp = remember(flameRows) {
        (MAX_FLAME_AREA_DP / flameRows).coerceIn(MIN_ROW_DP, PREFERRED_ROW_DP)
    }

    val rangeStart = remember(dataRevision) {
        minOf(
            actions.first().timestamp,
            spans.minOfOrNull { it.startMs } ?: Long.MAX_VALUE,
            markers.minOfOrNull { it.timestampMs } ?: Long.MAX_VALUE,
            networkEvents.minOfOrNull { it.event.startedAtMs } ?: Long.MAX_VALUE
        )
    }
    val rangeEnd = remember(dataRevision, crash) {
        val latest = maxOf(
            actions.last().timestamp,
            spans.maxOfOrNull { it.endMs } ?: Long.MIN_VALUE,
            markers.maxOfOrNull { it.timestampMs } ?: Long.MIN_VALUE,
            networkEvents.maxOfOrNull { it.event.startedAtMs + it.event.durationMs } ?: Long.MIN_VALUE,
            crash?.timestamp ?: Long.MIN_VALUE
        )
        val newestMarker = markers.maxOfOrNull { it.timestampMs } ?: Long.MIN_VALUE
        if (newestMarker >= latest) latest + TIMELINE_EDGE_PADDING_MS else latest
    }

    var window by remember { mutableStateOf<Pair<Long, Long>?>(null) }
    var hover by remember { mutableStateOf<TimelineHover?>(null) }
    val focusRequester = remember { FocusRequester() }

    val visibleStart = window?.first ?: rangeStart
    val visibleEnd = (window?.second ?: rangeEnd).coerceAtLeast(visibleStart + 1)
    val visibleSpanMs = visibleEnd - visibleStart

    fun clampWindow(start: Long, end: Long): Pair<Long, Long> {
        val span = (end - start).coerceIn(MIN_WINDOW_MS, (rangeEnd - rangeStart).coerceAtLeast(MIN_WINDOW_MS))
        val clampedStart = start.coerceIn(rangeStart, rangeEnd - span)
        return clampedStart to clampedStart + span
    }

    fun zoomAt(anchorMs: Long, factor: Float) {
        val newSpan = (visibleSpanMs * factor).toLong().coerceAtLeast(MIN_WINDOW_MS)
        if (newSpan >= rangeEnd - rangeStart) {
            window = null
            return
        }
        val fraction = (anchorMs - visibleStart).toFloat() / visibleSpanMs
        val newStart = anchorMs - (newSpan * fraction).toLong()
        window = clampWindow(newStart, newStart + newSpan)
    }

    fun pan(deltaMs: Long) {
        val current = window ?: return
        window = clampWindow(current.first + deltaMs, current.second + deltaMs)
    }

    val state = rememberUpdatedState(
        TimelineSnapshot(
            actions, spans, markers, crash, networkEvents, rangeStart, rangeEnd,
            visibleStart, visibleEnd, flameRows, rowDp, selectedActionIndex
        )
    )

    val colors = MaterialTheme.colorScheme
    val actionColor = colors.primary
    val logicColor = colors.secondary
    val dispatchColor = colors.secondary.copy(alpha = 0.35f)
    val phaseColor = colors.tertiary
    val stallColor = colors.error
    val markerColor = colors.tertiary
    val networkColor = Color(0xFF42A5F5)
    val selectionColor = colors.onSurface
    val laneColor = colors.outlineVariant
    val rulerTextColor = colors.onSurfaceVariant
    val labelColor = Color.White.copy(alpha = 0.95f)

    val textMeasurer = rememberTextMeasurer()
    val networkLaneDp = if (networkEvents.isEmpty()) 0f else NETWORK_LANE_DP
    val timelineHeight = (RULER_DP + TICK_LANE_DP + flameRows * rowDp + networkLaneDp + MARKER_LANE_DP).dp

    var legendVisible by remember { mutableStateOf(false) }
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { zoomAt((visibleStart + visibleEnd) / 2, 0.6f) }, modifier = Modifier.size(26.dp)) {
                Icon(Icons.Default.ZoomIn, contentDescription = "Zoom in", modifier = Modifier.size(16.dp))
            }
            IconButton(onClick = { zoomAt((visibleStart + visibleEnd) / 2, 1.7f) }, modifier = Modifier.size(26.dp)) {
                Icon(Icons.Default.ZoomOut, contentDescription = "Zoom out", modifier = Modifier.size(16.dp))
            }
            IconButton(onClick = { window = null }, modifier = Modifier.size(26.dp)) {
                Icon(Icons.Default.FitScreen, contentDescription = "Fit session", modifier = Modifier.size(16.dp))
            }
            Spacer(modifier = Modifier.width(8.dp))
            if (legendVisible) {
                LegendDot(actionColor, "Action")
                LegendDot(logicColor, "Logic")
                LegendDot(phaseColor, "Phase")
                LegendDot(stallColor, "Stall")
                LegendDot(markerColor, "Marker")
                if (networkEvents.isNotEmpty()) {
                    LegendDot(networkColor, "Network")
                }
            }
            IconButton(onClick = { legendVisible = !legendVisible }, modifier = Modifier.size(22.dp)) {
                Icon(
                    Icons.Default.Palette,
                    contentDescription = if (legendVisible) "Hide legend" else "Show legend",
                    tint = rulerTextColor,
                    modifier = Modifier.size(13.dp)
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            if (window != null) {
                Text(
                    text = "${formatOffset(visibleStart - rangeStart)} to ${formatOffset(visibleEnd - rangeStart)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = rulerTextColor
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            IconButton(
                onClick = onDropMarker,
                enabled = canDropMarker,
                modifier = Modifier.size(26.dp)
            ) {
                Icon(
                    Icons.Default.Flag,
                    contentDescription = if (canDropMarker) {
                        "Drop a marker at the pinned time"
                    } else {
                        "Click empty timeline space to pin a time, then drop a marker"
                    },
                    tint = if (canDropMarker) markerColor else markerColor.copy(alpha = 0.35f),
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(timelineHeight)
                .clipToBounds()
                .focusRequester(focusRequester)
                .focusable()
                .onKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                    val snapshot = state.value
                    val anchor = hover?.timeMs ?: (visibleStart + visibleEnd) / 2
                    when (event.key) {
                        Key.W -> {
                            zoomAt(anchor, 0.7f)
                            true
                        }
                        Key.S -> {
                            zoomAt(anchor, 1.45f)
                            true
                        }
                        Key.A -> {
                            pan(-(visibleSpanMs / 10))
                            true
                        }
                        Key.D -> {
                            pan(visibleSpanMs / 10)
                            true
                        }
                        Key.F -> {
                            window = null
                            true
                        }
                        Key.DirectionLeft -> {
                            val current = snapshot.selectedActionIndex ?: snapshot.actions.size
                            onSeek((current - 1).coerceAtLeast(0))
                            true
                        }
                        Key.DirectionRight -> {
                            val current = snapshot.selectedActionIndex ?: -1
                            onSeek((current + 1).coerceAtMost(snapshot.actions.size - 1))
                            true
                        }
                        else -> false
                    }
                }
                .pointerInput(Unit) {
                    val rulerPx = RULER_DP.dp.toPx()
                    val tickPx = TICK_LANE_DP.dp.toPx()
                    val networkLanePx = NETWORK_LANE_DP.dp.toPx()

                    fun snapshotGeometry(): TimelineGeometry {
                        val snapshot = state.value
                        val rowPx = snapshot.rowDp.dp.toPx()
                        return TimelineGeometry(
                            snapshot = snapshot,
                            widthPx = size.width.toFloat(),
                            rulerPx = rulerPx,
                            tickPx = tickPx,
                            rowPx = rowPx,
                            flameTopPx = rulerPx + tickPx,
                            flameBottomPx = rulerPx + tickPx + snapshot.flameRows * rowPx,
                            networkPx = if (snapshot.networkEvents.isEmpty()) 0f else networkLanePx
                        )
                    }

                    var lastClickAt = 0L
                    var lastClickX = 0f
                    var pressPosition: Offset? = null
                    var dragging = false

                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull() ?: continue
                            val geometry = snapshotGeometry()
                            when (event.type) {
                                PointerEventType.Scroll -> {
                                    val delta = change.scrollDelta.y
                                    if (delta != 0f) {
                                        val anchorMs = geometry.timeAt(change.position.x)
                                        zoomAt(anchorMs, if (delta > 0f) 1.25f else 0.8f)
                                        change.consume()
                                    }
                                }
                                PointerEventType.Move -> {
                                    if (pressPosition != null) {
                                        val previous = change.previousPosition
                                        val deltaPx = change.position.x - previous.x
                                        if (!dragging &&
                                            kotlin.math.abs(change.position.x - pressPosition!!.x) > viewConfiguration.touchSlop
                                        ) {
                                            dragging = true
                                        }
                                        if (dragging && deltaPx != 0f) {
                                            if (window == null && geometry.snapshot.visibleSpan < geometry.snapshot.fullSpan) {
                                                window = geometry.snapshot.visibleStart to geometry.snapshot.visibleEnd
                                            }
                                            pan(-(deltaPx / geometry.widthPx * geometry.snapshot.visibleSpan).toLong())
                                            change.consume()
                                        }
                                    } else {
                                        hover = geometry.hitTest(change.position)
                                    }
                                }
                                PointerEventType.Exit -> hover = null
                                PointerEventType.Press -> {
                                    pressPosition = change.position
                                    dragging = false
                                    focusRequester.requestFocus()
                                }
                                PointerEventType.Release -> {
                                    val pressed = pressPosition
                                    pressPosition = null
                                    if (pressed == null || dragging) continue
                                    val now = currentTimeMillis()
                                    val isDoubleClick = now - lastClickAt < DOUBLE_CLICK_MS &&
                                        kotlin.math.abs(change.position.x - lastClickX) < 8f
                                    lastClickAt = now
                                    lastClickX = change.position.x
                                    val hit = geometry.hitTest(change.position)
                                    when {
                                        hit?.actionIndex != null -> onSeek(hit.actionIndex)
                                        isDoubleClick && hit?.span != null -> {
                                            val span = hit.span
                                            onSelectSpan(span.callId)
                                            val pad = (span.durationMs / 5).coerceAtLeast(2L)
                                            window = clampWindow(span.startMs - pad, span.endMs + pad)
                                        }
                                        hit?.span != null -> onSelectSpan(hit.span.callId)
                                        isDoubleClick && hit?.network != null -> {
                                            val event = hit.network.event
                                            onSelectNetwork(event.id)
                                            val pad = (event.durationMs / 5).coerceAtLeast(2L)
                                            window = clampWindow(
                                                event.startedAtMs - pad,
                                                event.startedAtMs + event.durationMs + pad
                                            )
                                        }
                                        hit?.network != null -> onSelectNetwork(hit.network.event.id)
                                        hit?.marker != null -> {
                                            val index = hit.marker.afterActionIndex
                                                .coerceIn(0, geometry.snapshot.actions.size - 1)
                                            onSeek(index)
                                        }
                                        else -> {
                                            val tappedMs = hit?.timeMs ?: geometry.timeAt(change.position.x)
                                            onPinTime(tappedMs)
                                            val nearest = geometry.nearestActionIndex(tappedMs)
                                            if (nearest != null) onSeek(nearest)
                                        }
                                    }
                                }
                                else -> {}
                            }
                        }
                    }
                }
        ) {
            val snapshot = state.value
            val rulerPx = RULER_DP.dp.toPx()
            val tickPx = TICK_LANE_DP.dp.toPx()
            val rowPx = snapshot.rowDp.dp.toPx()
            val flameTop = rulerPx + tickPx
            val flameBottom = flameTop + snapshot.flameRows * rowPx
            val networkLanePx = if (snapshot.networkEvents.isEmpty()) 0f else NETWORK_LANE_DP.dp.toPx()
            val networkBottom = flameBottom + networkLanePx
            val visStart = snapshot.visibleStart
            val visSpan = snapshot.visibleSpan

            fun xFor(timestampMs: Long): Float =
                ((timestampMs - visStart).toFloat() / visSpan.toFloat()) * size.width

            drawLine(laneColor, Offset(0f, rulerPx), Offset(size.width, rulerPx), 1f)
            drawLine(laneColor, Offset(0f, flameTop), Offset(size.width, flameTop), 1f)
            drawLine(laneColor, Offset(0f, flameBottom), Offset(size.width, flameBottom), 1f)

            val step = niceTickStep(visSpan, size.width)
            var tickMs = ((visStart - snapshot.rangeStart) / step) * step + snapshot.rangeStart
            while (tickMs <= snapshot.visibleEnd) {
                if (tickMs >= visStart) {
                    val x = xFor(tickMs)
                    drawLine(laneColor, Offset(x, 4f), Offset(x, rulerPx), 1f)
                    drawText(
                        textMeasurer = textMeasurer,
                        text = formatOffset(tickMs - snapshot.rangeStart),
                        topLeft = Offset(x + 3f, 0f),
                        style = TextStyle(fontSize = 8.sp, color = rulerTextColor),
                        maxLines = 1
                    )
                }
                tickMs += step
            }

            snapshot.spans.forEach { span ->
                if (span.endMs < visStart || span.startMs > snapshot.visibleEnd) return@forEach
                val left = xFor(span.startMs)
                val width = (xFor(span.endMs) - left).coerceAtLeast(2f)
                val top = flameTop + span.depth * rowPx + 1f
                val color = when {
                    span.stall -> stallColor
                    span.failed -> stallColor.copy(alpha = 0.6f)
                    span.phase -> phaseColor
                    span.dispatch -> dispatchColor
                    else -> logicColor
                }
                drawRect(color, Offset(left, top), Size(width, rowPx - 2f))
                if (span.callId == selectedSpanCallId || span.callId == hover?.span?.callId) {
                    drawRect(
                        color = selectionColor,
                        topLeft = Offset(left, top),
                        size = Size(width, rowPx - 2f),
                        style = Stroke(width = if (span.callId == selectedSpanCallId) 2f else 1f)
                    )
                }
                if (width > LABEL_MIN_WIDTH_PX && rowPx >= 10f) {
                    drawText(
                        textMeasurer = textMeasurer,
                        text = span.name,
                        topLeft = Offset(left + 3f, top + (rowPx - 2f - 10.sp.toPx()) / 2f),
                        style = TextStyle(fontSize = 8.sp, color = labelColor),
                        overflow = TextOverflow.Ellipsis,
                        softWrap = false,
                        maxLines = 1,
                        size = Size((width - 5f).coerceAtLeast(1f), rowPx)
                    )
                }
            }

            snapshot.actions.forEachIndexed { index, event ->
                if (event.timestamp < visStart || event.timestamp > snapshot.visibleEnd) return@forEachIndexed
                val x = xFor(event.timestamp)
                val selected = index == snapshot.selectedActionIndex
                val hoveredAction = index == hover?.actionIndex
                drawLine(
                    color = when {
                        selected -> selectionColor
                        hoveredAction -> selectionColor.copy(alpha = 0.8f)
                        else -> actionColor
                    },
                    start = Offset(x, rulerPx + 2f),
                    end = Offset(x, flameTop - 2f),
                    strokeWidth = when {
                        selected -> 4f
                        hoveredAction -> 4f
                        else -> 2.5f
                    }
                )
                drawCircle(
                    color = if (selected || hoveredAction) selectionColor else actionColor,
                    radius = if (selected || hoveredAction) 3.5f else 2.5f,
                    center = Offset(x, rulerPx + 4f)
                )
                if (selected) {
                    drawLine(selectionColor.copy(alpha = 0.35f), Offset(x, 0f), Offset(x, size.height), 1f)
                }
            }

            if (networkLanePx > 0f) {
                drawLine(laneColor, Offset(0f, networkBottom), Offset(size.width, networkBottom), 1f)
                snapshot.networkEvents.forEach { row ->
                    val event = row.event
                    val endMs = event.startedAtMs + event.durationMs
                    if (endMs < visStart || event.startedAtMs > snapshot.visibleEnd) return@forEach
                    val left = xFor(event.startedAtMs)
                    val width = (xFor(endMs) - left).coerceAtLeast(2f)
                    val top = flameBottom + 2f
                    val barColor = if (event.isFailure) stallColor else networkColor
                    drawRect(barColor, Offset(left, top), Size(width, networkLanePx - 4f))
                    val highlighted = event.id == selectedNetworkRequestId ||
                        event.id == hover?.network?.event?.id
                    if (highlighted) {
                        drawRect(
                            color = selectionColor,
                            topLeft = Offset(left, top),
                            size = Size(width, networkLanePx - 4f),
                            style = Stroke(width = if (event.id == selectedNetworkRequestId) 2f else 1f)
                        )
                    }
                }
            }

            snapshot.markers.forEach { marker ->
                if (marker.timestampMs < visStart || marker.timestampMs > snapshot.visibleEnd) return@forEach
                val x = xFor(marker.timestampMs)
                val top = networkBottom + 2f
                val bottom = size.height - 2f
                val flag = Path().apply {
                    moveTo(x, top)
                    lineTo(x + 8f, (top + bottom) / 2f)
                    lineTo(x, bottom)
                    close()
                }
                drawPath(flag, markerColor)
                drawLine(markerColor.copy(alpha = 0.5f), Offset(x, 0f), Offset(x, size.height), 1f)
            }

            snapshot.crash?.let { info ->
                if (info.timestamp in visStart..snapshot.visibleEnd) {
                    drawLine(stallColor, Offset(xFor(info.timestamp), 0f), Offset(xFor(info.timestamp), size.height), 3f)
                }
            }

            pinnedTimeMs?.let { pinned ->
                if (pinned in visStart..snapshot.visibleEnd) {
                    val x = xFor(pinned)
                    drawLine(
                        color = markerColor,
                        start = Offset(x, 0f),
                        end = Offset(x, size.height),
                        strokeWidth = 2f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 4f))
                    )
                }
            }

            hover?.let { info ->
                if (info.timeMs in visStart..snapshot.visibleEnd) {
                    val x = xFor(info.timeMs)
                    drawLine(selectionColor.copy(alpha = 0.25f), Offset(x, 0f), Offset(x, size.height), 1f)
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().height(20.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val hovered = hover
            val detail = when {
                hovered?.span != null -> {
                    val span = hovered.span
                    "${span.fullName}  ${formatDuration(span.durationMs)} (self ${formatDuration(span.selfMs)})" +
                        "  at ${formatOffset(span.startMs - rangeStart)}"
                }
                hovered?.actionIndex != null -> {
                    val event = actions.getOrNull(hovered.actionIndex)
                    "Action: ${event?.actionType ?: ""}  at " +
                        formatOffset((event?.timestamp ?: rangeStart) - rangeStart) +
                        "  click to select in the stream"
                }
                hovered?.network != null -> {
                    val event = hovered.network.event
                    val status = event.error ?: event.responseStatus?.toString() ?: "?"
                    "${event.method} ${networkPathLabel(event.url)}  $status  ${formatDuration(event.durationMs)}" +
                        "  at ${formatOffset(event.startedAtMs - rangeStart)}  click for details"
                }
                hovered?.marker != null ->
                    "Marker: ${hovered.marker.label}  at ${formatOffset(hovered.marker.timestampMs - rangeStart)}" +
                        "  (click to jump)"
                hovered != null -> "cursor ${formatOffset(hovered.timeMs - rangeStart)}"
                pinnedTimeMs != null ->
                    "marker target pinned at ${formatOffset(pinnedTimeMs - rangeStart)}  press the flag or m to drop a marker"
                else -> "Click empty space to pin a marker target.  Wheel or W/S zooms, drag or A/D pans, F fits."
            }
            Text(
                text = detail,
                style = MaterialTheme.typography.labelSmall,
                color = rulerTextColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        modifier = Modifier.padding(end = 8.dp)
    ) {
        Box(modifier = Modifier.size(7.dp).clip(CircleShape).background(color))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private class TimelineSnapshot(
    val actions: List<CapturedAction>,
    val spans: List<FlameSpan>,
    val markers: List<SessionMarker>,
    val crash: CrashInfo?,
    val networkEvents: List<NetworkEventRow>,
    val rangeStart: Long,
    val rangeEnd: Long,
    val visibleStart: Long,
    val visibleEnd: Long,
    val flameRows: Int,
    val rowDp: Float,
    val selectedActionIndex: Int?
) {
    val visibleSpan: Long get() = (visibleEnd - visibleStart).coerceAtLeast(1L)
    val fullSpan: Long get() = (rangeEnd - rangeStart).coerceAtLeast(1L)
}

private class TimelineGeometry(
    val snapshot: TimelineSnapshot,
    val widthPx: Float,
    val rulerPx: Float,
    val tickPx: Float,
    val rowPx: Float,
    val flameTopPx: Float,
    val flameBottomPx: Float,
    val networkPx: Float
) {
    fun timeAt(x: Float): Long =
        snapshot.visibleStart + (x / widthPx * snapshot.visibleSpan).toLong()

    fun hitTest(position: Offset): TimelineHover {
        val timeMs = timeAt(position.x)
        if (position.y in rulerPx..flameTopPx) {
            val toleranceMs = (10f / widthPx * snapshot.visibleSpan).toLong().coerceAtLeast(1L)
            val nearest = snapshot.actions.withIndex().minByOrNull { (_, event) ->
                val distance = event.timestamp - timeMs
                if (distance < 0) -distance else distance
            }
            if (nearest != null) {
                val distance = nearest.value.timestamp - timeMs
                if ((if (distance < 0) -distance else distance) <= toleranceMs) {
                    return TimelineHover(timeMs, actionIndex = nearest.index)
                }
            }
        }
        if (position.y in flameTopPx..flameBottomPx) {
            val row = ((position.y - flameTopPx) / rowPx).toInt()
            val span = snapshot.spans
                .filter { it.depth == row && timeMs >= it.startMs && timeMs <= it.endMs }
                .minByOrNull { it.durationMs }
            if (span != null) return TimelineHover(timeMs, span = span)
        }
        if (networkPx > 0f && position.y in flameBottomPx..(flameBottomPx + networkPx)) {
            val toleranceMs = (4f / widthPx * snapshot.visibleSpan).toLong().coerceAtLeast(1L)
            val hitRow = snapshot.networkEvents.lastOrNull { row ->
                timeMs >= row.event.startedAtMs - toleranceMs &&
                    timeMs <= row.event.startedAtMs + row.event.durationMs + toleranceMs
            }
            if (hitRow != null) return TimelineHover(timeMs, network = hitRow)
        }
        if (position.y > flameBottomPx + networkPx) {
            val toleranceMs = (12f / widthPx * snapshot.visibleSpan).toLong().coerceAtLeast(1L)
            val marker = snapshot.markers.minByOrNull {
                val distance = it.timestampMs - timeMs
                if (distance < 0) -distance else distance
            }
            if (marker != null) {
                val distance = marker.timestampMs - timeMs
                if ((if (distance < 0) -distance else distance) <= toleranceMs) {
                    return TimelineHover(timeMs, marker = marker)
                }
            }
        }
        return TimelineHover(timeMs)
    }

    fun nearestActionIndex(timeMs: Long): Int? =
        snapshot.actions.withIndex().minByOrNull { (_, event) ->
            val distance = event.timestamp - timeMs
            if (distance < 0) -distance else distance
        }?.index
}
