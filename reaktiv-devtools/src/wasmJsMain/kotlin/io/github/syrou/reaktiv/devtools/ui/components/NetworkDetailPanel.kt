package io.github.syrou.reaktiv.devtools.ui.components

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.FilterChip
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.syrou.reaktiv.devtools.protocol.CurlFormatter
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.text.style.TextAlign
import io.github.syrou.reaktiv.devtools.ui.DevToolsColors
import io.github.syrou.reaktiv.devtools.ui.EndpointStats
import io.github.syrou.reaktiv.devtools.ui.NetworkFilter
import io.github.syrou.reaktiv.devtools.ui.NetworkSort
import io.github.syrou.reaktiv.devtools.ui.applyNetworkFilter
import io.github.syrou.reaktiv.devtools.ui.endpointStats
import io.github.syrou.reaktiv.devtools.ui.BodyRender
import io.github.syrou.reaktiv.devtools.ui.resolveBodyView
import io.github.syrou.reaktiv.devtools.ui.NetworkBodyLoad
import io.github.syrou.reaktiv.devtools.ui.NetworkEventRow
import io.github.syrou.reaktiv.devtools.ui.networkBodyKey
import io.github.syrou.reaktiv.introspection.network.NetworkBodyPart
import kotlinx.serialization.json.Json

private val BODY_PANE_HEIGHT = 380.dp

private val prettyJson = Json { prettyPrint = true }

internal fun prettyPrintIfJson(body: String): String =
    runCatching {
        val trimmed = body.trim()
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) return body
        prettyJson.encodeToString(
            kotlinx.serialization.json.JsonElement.serializer(),
            Json.parseToJsonElement(trimmed)
        )
    }.getOrDefault(body)

@Composable
internal fun NetworkDetailPanel(
    networkEvents: List<NetworkEventRow>,
    selectedRequestId: String?,
    bodies: Map<String, NetworkBodyLoad>,
    filter: NetworkFilter,
    showStats: Boolean,
    onSelectRequest: (String?) -> Unit,
    onFetchBody: (String, NetworkBodyPart) -> Unit,
    onFilterChange: (NetworkFilter) -> Unit,
    onToggleStats: () -> Unit,
    onExportHar: () -> Unit
) {
    val selected = networkEvents.lastOrNull { it.event.id == selectedRequestId }
    if (selected == null) {
        NetworkOverviewList(
            networkEvents = networkEvents,
            filter = filter,
            showStats = showStats,
            onSelectRequest = onSelectRequest,
            onFilterChange = onFilterChange,
            onToggleStats = onToggleStats,
            onExportHar = onExportHar
        )
    } else {
        NetworkRequestDetail(
            row = selected,
            bodies = bodies,
            onSelectRequest = onSelectRequest,
            onFetchBody = onFetchBody
        )
    }
}

@Composable
private fun NetworkOverviewList(
    networkEvents: List<NetworkEventRow>,
    filter: NetworkFilter,
    showStats: Boolean,
    onSelectRequest: (String?) -> Unit,
    onFilterChange: (NetworkFilter) -> Unit,
    onToggleStats: () -> Unit,
    onExportHar: () -> Unit
) {
    val visible = remember(networkEvents, filter) { networkEvents.applyNetworkFilter(filter) }
    val methods = remember(networkEvents) {
        networkEvents.map { it.event.method.uppercase() }.distinct().sorted()
    }

    Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Network",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f)
            )
            if (networkEvents.isNotEmpty()) {
                FilterChip(
                    selected = showStats,
                    onClick = onToggleStats,
                    label = { Text("By endpoint", style = MaterialTheme.typography.labelSmall, maxLines = 1) }
                )
                Spacer(modifier = Modifier.width(6.dp))
                AssistChip(
                    onClick = onExportHar,
                    label = { Text("HAR", style = MaterialTheme.typography.labelSmall, maxLines = 1) }
                )
            }
        }

        if (networkEvents.isEmpty()) {
            Text(
                text = "No requests captured yet." +
                    "\n\n1. Add reaktiv-network-ktor to the app" +
                    "\n2. install(ReaktivNetworkInspection) on the HttpClient making the calls" +
                    "\n3. Check the device shows as a publisher in the client list" +
                    "\n\nCapture only runs while this UI is attached, so requests made before " +
                    "connecting do not appear.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            return@Column
        }

        Spacer(modifier = Modifier.height(6.dp))
        OutlinedTextField(
            value = filter.query,
            onValueChange = { onFilterChange(filter.copy(query = it)) },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Filter by url, method or status", style = MaterialTheme.typography.bodySmall) },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodySmall
        )
        Spacer(modifier = Modifier.height(6.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            FilterChip(
                selected = filter.failuresOnly,
                onClick = { onFilterChange(filter.copy(failuresOnly = !filter.failuresOnly)) },
                label = { Text("Failures", style = MaterialTheme.typography.labelSmall, maxLines = 1) }
            )
            methods.forEach { method ->
                FilterChip(
                    selected = method in filter.methods,
                    onClick = {
                        val next = if (method in filter.methods) {
                            filter.methods - method
                        } else {
                            filter.methods + method
                        }
                        onFilterChange(filter.copy(methods = next))
                    },
                    label = { Text(method, style = MaterialTheme.typography.labelSmall, maxLines = 1) }
                )
            }
            NetworkSort.entries.forEach { sort ->
                FilterChip(
                    selected = filter.sort == sort,
                    onClick = { onFilterChange(filter.copy(sort = sort)) },
                    label = { Text(sort.label, style = MaterialTheme.typography.labelSmall, maxLines = 1) }
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = if (visible.size == networkEvents.size) {
                "${networkEvents.size} requests"
            } else {
                "${visible.size} of ${networkEvents.size} requests"
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(4.dp))

        val listState = rememberLazyListState()
        Box(modifier = Modifier.fillMaxSize()) {
            if (showStats) {
                val stats = remember(visible) { visible.endpointStats() }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().padding(end = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(1.dp)
                ) {
                    items(stats) { stat -> EndpointStatRow(stat) }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().padding(end = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(1.dp)
                ) {
                    items(visible) { row -> NetworkListRow(row, onSelectRequest) }
                }
            }
            VerticalScrollbar(
                adapter = rememberScrollbarAdapter(listState),
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight()
            )
        }
    }
}

@Composable
private fun NetworkListRow(row: NetworkEventRow, onSelectRequest: (String?) -> Unit) {
    val event = row.event
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .clickable { onSelectRequest(event.id) }
            .padding(horizontal = 6.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = event.method,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(44.dp)
        )
        Text(
            text = networkPathLabel(event.url),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = when {
                event.error != null -> "failed"
                event.decodeError != null -> "${event.responseStatus ?: "?"} decode"
                else -> "${event.responseStatus ?: "?"}"
            },
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = networkStatusColor(event.responseStatus, event.error ?: event.decodeError)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "${event.durationMs}ms",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(60.dp),
            textAlign = TextAlign.End
        )
    }
}

@Composable
private fun EndpointStatRow(stat: EndpointStats) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 3.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stat.method,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(44.dp)
            )
            Text(
                text = stat.path,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "${stat.calls}x",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace
            )
        }
        Row {
            Spacer(modifier = Modifier.width(44.dp))
            Text(
                text = buildString {
                    append("median ${stat.medianMs}ms, slowest ${stat.slowestMs}ms")
                    append(", ${formatBytes(stat.totalBytes)}")
                    if (stat.failures > 0) append(", ${stat.failures} failed")
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (stat.failures > 0) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
    }
}

private const val LINE_BREAK = "\n"

internal fun renderHeaderLines(headers: Map<String, List<String>>): String =
    headers.entries
        .flatMap { (name, values) -> values.map { "$name: $it" } }
        .joinToString(LINE_BREAK)

internal fun parseHeaderLines(text: String): Map<String, List<String>> {
    val parsed = LinkedHashMap<String, MutableList<String>>()
    text.lineSequence().forEach { line ->
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return@forEach
        val separator = trimmed.indexOf(':')
        if (separator <= 0) return@forEach
        val name = trimmed.substring(0, separator).trim()
        val value = trimmed.substring(separator + 1).trim()
        if (name.isEmpty()) return@forEach
        parsed.getOrPut(name) { mutableListOf() }.add(value)
    }
    return parsed
}

@Composable
private fun TimingBar(waitMs: Long, downloadMs: Long, totalMs: Long) {
    val total = maxOf(totalMs, waitMs + downloadMs, 1L).toFloat()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        val waitWeight = (waitMs / total).coerceIn(0.001f, 1f)
        val downloadWeight = (downloadMs / total).coerceIn(0.001f, 1f)
        val restWeight = (1f - waitWeight - downloadWeight).coerceAtLeast(0.001f)
        Box(
            modifier = Modifier
                .weight(waitWeight)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.tertiary)
        )
        Box(
            modifier = Modifier
                .weight(downloadWeight)
                .fillMaxHeight()
                .background(DevToolsColors.success)
        )
        Box(modifier = Modifier.weight(restWeight).fillMaxHeight())
    }
}

@Composable
private fun NetworkFailureBlock(title: String, detail: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f))
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error
        )
        Text(
            text = detail,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.error
        )
    }
}

@Composable
private fun networkStatusColor(status: Int?, error: String?) = when {
    error != null -> MaterialTheme.colorScheme.error
    status == null -> MaterialTheme.colorScheme.onSurfaceVariant
    status >= 500 -> MaterialTheme.colorScheme.error
    status >= 400 -> DevToolsColors.warning
    status >= 300 -> MaterialTheme.colorScheme.tertiary
    else -> DevToolsColors.success
}

private val NetworkBodyPart.label: String
    get() = if (this == NetworkBodyPart.REQUEST) "Request" else "Response"

@Composable
private fun NetworkRequestDetail(
    row: NetworkEventRow,
    bodies: Map<String, NetworkBodyLoad>,
    onSelectRequest: (String?) -> Unit,
    onFetchBody: (String, NetworkBodyPart) -> Unit,
) {
    val event = row.event
    var copied by remember(event.id) { mutableStateOf<String?>(null) }
    var part by remember(event.id) { mutableStateOf(NetworkBodyPart.RESPONSE) }
    var headersExpanded by remember(event.id) { mutableStateOf(false) }
    var editingReplay by remember(event.id) { mutableStateOf(false) }
    var editMethod by remember(event.id) { mutableStateOf(event.method) }
    var editUrl by remember(event.id) { mutableStateOf(event.url) }
    var editBody by remember(event.id) { mutableStateOf(event.requestBody.orEmpty()) }
    val originalHeaderText = remember(event.id) { renderHeaderLines(event.requestHeaders) }
    var editHeaders by remember(event.id) { mutableStateOf(originalHeaderText) }
    val metaScroll = rememberScrollState()

    fun copy(label: String, text: String) {
        copyTextToClipboard(text)
        copied = label
    }

    val headers = if (part == NetworkBodyPart.REQUEST) event.requestHeaders else event.responseHeaders
    val preview = if (part == NetworkBodyPart.REQUEST) event.requestBody else event.responseBody
    val truncated =
        if (part == NetworkBodyPart.REQUEST) event.requestBodyTruncated else event.responseBodyTruncated
    val contentType =
        if (part == NetworkBodyPart.REQUEST) event.requestContentType else event.responseContentType
    val load = bodies[networkBodyKey(event.id, part)]

    LaunchedEffect(event.id, part, truncated) {
        if (truncated && load == null) {
            onFetchBody(event.id, part)
        }
    }

    val body = when {
        load != null && load.complete && !load.unavailable -> load.text
        load != null && load.receivedBytes > 0 -> load.text
        else -> preview
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(metaScroll),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TextButton(
                onClick = { onSelectRequest(null) },
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
            ) {
                Text("< All requests", style = MaterialTheme.typography.labelSmall)
            }

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = event.method,
                        style = MaterialTheme.typography.titleSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = event.error
                            ?: event.responseStatus?.let { "$it ${event.responseStatusText.orEmpty()}" }
                            ?: "no response",
                        style = MaterialTheme.typography.titleSmall,
                        color = if (event.isFailure) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.secondary
                        }
                    )
                }
                Text(
                    text = event.url,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                SummaryRow("Started", formatClockTime(event.startedAtMs))
                SummaryRow(
                    "Duration",
                    buildString {
                        append("${event.durationMs}ms")
                        val wait = event.waitMs
                        val download = event.downloadMs
                        if (wait != null) {
                            append("  (waited ${wait}ms")
                            if (download != null) append(", downloaded ${download}ms")
                            append(")")
                        }
                    }
                )
                val waited = event.waitMs
                if (waited != null && event.durationMs > 0) {
                    TimingBar(
                        waitMs = waited,
                        downloadMs = event.downloadMs ?: 0,
                        totalMs = event.durationMs
                    )
                }
                SummaryRow(
                    "Sent",
                    buildString {
                        append(if (event.requestBodySize > 0) formatBytes(event.requestBodySize) else "no body")
                        event.requestContentType?.let { append("  $it") }
                    }
                )
                SummaryRow(
                    "Received",
                    buildString {
                        append(if (event.responseBodySize >= 0) formatBytes(event.responseBodySize) else "unknown")
                        event.responseContentType?.let { append("  $it") }
                    }
                )
                if (row.clientId.isNotBlank()) {
                    SummaryRow("Device", row.clientId)
                }
            }

            event.error?.let { error ->
                NetworkFailureBlock(title = "Request failed", detail = error)
            }

            event.decodeError?.let { decodeError ->
                NetworkFailureBlock(
                    title = "Response did not match the expected type",
                    detail = decodeError
                )
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                AssistChip(
                    onClick = { copy("curl", CurlFormatter.toCurl(event)) },
                    label = {
                        Text(
                            if (copied == "curl") "Copied" else "Copy cURL",
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1
                        )
                    }
                )
                AssistChip(
                    onClick = { copy("url", event.url) },
                    label = {
                        Text(
                            if (copied == "url") "Copied" else "Copy URL",
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1
                        )
                    }
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                NetworkBodyPart.entries.forEach { entry ->
                    FilterChip(
                        selected = part == entry,
                        onClick = { part = entry },
                        label = { Text(entry.label, style = MaterialTheme.typography.labelSmall, maxLines = 1) }
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = if (headersExpanded) "v Headers (${headers.size})" else "> Headers (${headers.size})",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(4.dp))
                        .clickable { headersExpanded = !headersExpanded }
                        .padding(vertical = 4.dp)
                )
                if (headersExpanded) {
                    if (headers.isEmpty()) {
                        Text(
                            text = "(none)",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        headers.forEach { (key, values) ->
                            values.forEach { value ->
                                Row(modifier = Modifier.fillMaxWidth()) {
                                    Text(
                                        text = key,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.width(150.dp)
                                    )
                                    Text(
                                        text = value,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = FontFamily.Monospace,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }
                    }
                }
            }
            BodyPane(
                title = "${part.label} body",
                body = body,
                contentType = contentType,
                truncated = truncated,
                declaredSize = if (part == NetworkBodyPart.REQUEST) {
                    event.requestBodySize
                } else {
                    event.responseBodySize
                },
                load = load,
                copiedLabel = copied,
                onCopy = ::copy,
                onRetry = { onFetchBody(event.id, part) },
                modifier = Modifier.fillMaxWidth().height(BODY_PANE_HEIGHT)
            )
        }
    }
}

@Composable
private fun SummaryRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(80.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(1f)
        )
    }
}

private fun bodyAbsenceReason(contentType: String?, size: Long): String = when {
    contentType == null && size <= 0 -> "No body"
    contentType == null -> "Body of ${formatBytes(size)} not captured"
    size > 0 -> "${formatBytes(size)} of $contentType, not captured because it is not textual"
    else -> "Body not captured for $contentType"
}

@Composable
private fun BodyPane(
    title: String,
    body: String?,
    contentType: String?,
    truncated: Boolean,
    declaredSize: Long,
    load: NetworkBodyLoad?,
    copiedLabel: String?,
    onCopy: (String, String) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier
) {
    val view = remember(body, load, truncated, contentType) {
        resolveBodyView(preview = body, load = load, truncated = truncated, contentType = contentType)
    }
    val streaming = view.streaming
    val complete = load != null && load.complete && !load.unavailable
    val isJson = view.treeAvailable
    var rawView by remember(body) { mutableStateOf(view.render != BodyRender.TREE) }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = buildString {
                    append(title)
                    val progress = load
                    when {
                        streaming && progress != null && progress.totalBytes > 0 ->
                            append(
                                " loading ${formatBytes(progress.receivedBytes.toLong())}" +
                                    " of ${formatBytes(progress.totalBytes.toLong())}"
                            )
                        streaming -> append(" loading")
                        complete && progress != null -> append(" ${formatBytes(progress.totalBytes.toLong())}")
                        truncated -> append(" (preview)")
                    }
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            if (isJson) {
                AssistChip(
                    onClick = { rawView = !rawView },
                    label = {
                        Text(
                            if (rawView) "Tree" else "Raw",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                )
                Spacer(modifier = Modifier.width(6.dp))
            }
            if (body != null) {
                AssistChip(
                    onClick = { onCopy("body", body) },
                    label = {
                        Text(
                            if (copiedLabel == "body") "Copied" else "Copy",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                )
            }
        }

        if (view.text == null) {
            Text(
                text = bodyAbsenceReason(contentType, declaredSize),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            view.note?.let { note ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = note,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (view.streaming) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                        modifier = Modifier.weight(1f)
                    )
                    AssistChip(
                        onClick = onRetry,
                        label = { Text("Retry", style = MaterialTheme.typography.labelSmall) }
                    )
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            ) {
                if (isJson && !rawView) {
                    JsonTreeViewer(jsonString = view.text, modifier = Modifier.fillMaxSize())
                } else {
                    val scroll = rememberScrollState()
                    Text(
                        text = if (isJson) prettyPrintIfJson(view.text) else view.text,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(scroll)
                            .padding(start = 6.dp, top = 6.dp, bottom = 6.dp, end = 14.dp)
                    )
                    VerticalScrollbar(
                        adapter = rememberScrollbarAdapter(scroll),
                        modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight()
                    )
                }
            }
        }
    }
}
