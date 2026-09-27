package io.github.syrou.reaktiv.devtools.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import io.github.syrou.reaktiv.devtools.ui.DevToolsColors
import io.github.syrou.reaktiv.devtools.ui.LinkAttempt
import io.github.syrou.reaktiv.devtools.ui.RequestStatus
import io.github.syrou.reaktiv.devtools.ui.LinkDraft
import io.github.syrou.reaktiv.devtools.ui.LocalSyntaxColors
import io.github.syrou.reaktiv.devtools.ui.navmap.MapAccess
import io.github.syrou.reaktiv.devtools.ui.navmap.MapGraph
import io.github.syrou.reaktiv.devtools.ui.navmap.MapKind
import io.github.syrou.reaktiv.devtools.ui.navmap.MapLinkOutcome
import io.github.syrou.reaktiv.devtools.ui.navmap.MapModel
import io.github.syrou.reaktiv.devtools.ui.navmap.MapRoute
import io.github.syrou.reaktiv.devtools.ui.navmap.MapStart
import io.github.syrou.reaktiv.devtools.ui.navmap.aliasesTo
import io.github.syrou.reaktiv.devtools.ui.navmap.graphChain
import io.github.syrou.reaktiv.devtools.ui.navmap.graphsStartingAt
import io.github.syrou.reaktiv.devtools.ui.navmap.parseLinkOutcome
import io.github.syrou.reaktiv.devtools.ui.navmap.webPathOf

internal fun MapAccess.explanation(): String = when (this) {
    MapAccess.Linkable -> "A link can open this route"
    MapAccess.Fallback -> "Shown when a link matches no route"
    MapAccess.Internal -> "Not reachable by a link (system layer, loading or crash screen)"
    MapAccess.Shadowed -> "Another route with the same shape wins, so links never land here"
}

internal fun linkFor(route: MapRoute, draft: LinkDraft): String {
    val query = draft.query.trim().removePrefix("?")
    return if (query.isEmpty()) route.path else "${route.path}?$query"
}

internal fun LinkAttempt.describe(): String = call.progressText ?: when (val outcome = parseLinkOutcome(result)) {
    is MapLinkOutcome.Landed -> "Landed on ${outcome.location}"
    is MapLinkOutcome.Redirected -> "A guard redirected it to ${outcome.to}"
    MapLinkOutcome.Rejected -> "A guard rejected it"
    is MapLinkOutcome.NotFound -> "No route matched. ${outcome.reason}"
    is MapLinkOutcome.MissingParams -> "Fill in ${outcome.names.joinToString()}"
    is MapLinkOutcome.Ignored -> "Ignored: ${outcome.reason}"
    null -> "Answered"
}

private fun routeTitle(path: String, param: Color, text: Color) = buildAnnotatedString {
    var index = 0
    while (index < path.length) {
        val open = path.indexOf('{', index)
        if (open < 0) {
            withStyle(SpanStyle(color = text)) { append(path.substring(index)) }
            break
        }
        withStyle(SpanStyle(color = text)) { append(path.substring(index, open)) }
        val close = path.indexOf('}', open).takeIf { it > open } ?: path.lastIndex
        withStyle(SpanStyle(color = param)) { append(path.substring(open, close + 1)) }
        index = close + 1
    }
}

@Composable
internal fun RouteDetail(
    model: MapModel,
    route: MapRoute,
    draft: LinkDraft,
    attempts: List<LinkAttempt>,
    openBlockedReason: String?,
    onDraftChange: (LinkDraft) -> Unit,
    onOpen: (link: String, params: Map<String, String>) -> Unit,
    onSelectGraph: (String) -> Unit,
    onSelectRoute: (String) -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val aliases = model.aliasesTo(route.path)
    val webPath = model.webPathOf(route)
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = routeTitle(route.path, LocalSyntaxColors.current.number, colors.primary),
                style = MaterialTheme.typography.titleSmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f)
            )
            CopyControl(
                actions = buildList {
                    add(CopyAction("Path") { route.path })
                    webPath?.let { add(CopyAction("Web path") { it }) }
                    aliases.forEach { alias -> add(CopyAction("Alias ${alias.pattern}") { alias.pattern }) }
                }
            )
        }
        FieldRow("Screen", route.screen ?: "anonymous screen object", mono = true)
        FieldRow("Kind", if (route.kind == MapKind.Modal) "Modal" else "Screen")
        FieldRow("Link", route.access.explanation())
        if (route.params.isNotEmpty()) FieldRow("Params", route.params.joinToString(), mono = true)
        webPath?.let { FieldRow("Web path", it, mono = true) }

        SectionHeading("Graph")
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
            model.graphChain(route.graph).forEachIndexed { index, graph ->
                if (index > 0) Caption("/")
                TextButton(onClick = { onSelectGraph(graph.id) }) {
                    Text(if (graph.id == "root") "App" else graph.id, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        val startOf = model.graphsStartingAt(route.path)
        if (startOf.isNotEmpty()) {
            Caption("Start of ${startOf.joinToString { if (it.id == "root") "the app" else it.id }}")
        }
        if (route.guards.isNotEmpty()) {
            Caption("Behind guard ${route.guards.joinToString(" then ")}. A link runs these first and may be redirected or rejected.")
        }

        if (aliases.isNotEmpty()) {
            SectionHeading("Aliases", aliases.size.toString())
            aliases.forEach { alias ->
                Text(alias.pattern, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = colors.onSurface)
            }
        }

        SectionHeading("Open on device")
        route.params.forEach { name ->
            OutlinedTextField(
                value = draft.params[name].orEmpty(),
                onValueChange = { onDraftChange(draft.copy(params = draft.params + (name to it))) },
                label = { Text(name) },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth()
            )
        }
        OutlinedTextField(
            value = draft.query,
            onValueChange = { onDraftChange(draft.copy(query = it)) },
            label = { Text("Query (optional)") },
            placeholder = { Text("tab=stats&ref=newsletter") },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier.fillMaxWidth()
        )
        val blocked = openBlockedReason ?: when {
            !route.canOpen -> route.access.explanation()
            else -> null
        }
        Button(
            onClick = { onOpen(linkFor(route, draft), draft.params.filterValues { it.isNotBlank() }) },
            enabled = blocked == null
        ) {
            Text("Open on device")
        }
        Caption(blocked ?: "Opens the route the way a universal link does. The app's back stack is replaced and guards run.")

        val mine = attempts.filter { it.path == route.path }.asReversed()
        if (mine.isNotEmpty()) {
            SectionHeading("Attempts", mine.size.toString())
            mine.take(8).forEach { attempt ->
                val color = when {
                    attempt.status == RequestStatus.PENDING -> colors.onSurfaceVariant
                    attempt.status == RequestStatus.UNANSWERED || attempt.error != null -> colors.error
                    parseLinkOutcome(attempt.result) is MapLinkOutcome.Landed -> DevToolsColors.success
                    else -> DevToolsColors.warning
                }
                Column {
                    Text(attempt.describe(), style = MaterialTheme.typography.labelSmall, color = color)
                    Caption(attempt.link, singleLine = true)
                }
            }
        }
        if (route.access != MapAccess.Linkable) {
            Caption("Routes that a link cannot reach are still listed, so the map shows everything the app registers.")
        }
        val sameScreen = model.routes.filter { it.screen != null && it.screen == route.screen && it.path != route.path }
        if (sameScreen.isNotEmpty()) {
            SectionHeading("Same screen elsewhere")
            sameScreen.forEach { other ->
                TextButton(onClick = { onSelectRoute(other.path) }) {
                    Text(other.path, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}

@Composable
internal fun GraphDetail(
    model: MapModel,
    graph: MapGraph,
    collapsed: Boolean,
    onSelectRoute: (String) -> Unit,
    onSelectGraph: (String) -> Unit,
    onToggle: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = if (graph.id == "root") "App" else graph.id,
            style = MaterialTheme.typography.titleSmall,
            color = colors.primary
        )
        FieldRow("Path", if (graph.path.isEmpty()) "/" else "/${graph.path}", mono = true)
        graph.parent?.let { parent ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Caption("Inside")
                TextButton(onClick = { onSelectGraph(parent) }) {
                    Text(if (parent == "root") "App" else parent, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        when (val start = graph.start) {
            is MapStart.Route -> Row(verticalAlignment = Alignment.CenterVertically) {
                Caption("Starts on")
                TextButton(onClick = { onSelectRoute(start.path) }) {
                    Text(start.path, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                }
            }
            is MapStart.Graph -> Row(verticalAlignment = Alignment.CenterVertically) {
                Caption("Starts in graph")
                TextButton(onClick = { onSelectGraph(start.id) }) {
                    Text(start.id, style = MaterialTheme.typography.labelSmall)
                }
            }
            MapStart.Dynamic -> Caption("Its start is decided at runtime by the graph's start lambda, so a link to the graph path runs it.")
            MapStart.None -> Caption("It has no start of its own. Links must name a route inside it.")
        }
        if (graph.guards.isNotEmpty()) {
            Caption("Guarded by ${graph.guards.joinToString(" then ")}. Every route inside runs these guards when opened by a link.")
        }
        TextButton(onClick = onToggle) { Text(if (collapsed) "Unfold on the map" else "Fold on the map") }

        val routes = model.routes.filter { it.graph == graph.id }
        if (routes.isNotEmpty()) {
            SectionHeading("Routes", routes.size.toString())
            routes.forEach { route ->
                TextButton(onClick = { onSelectRoute(route.path) }) {
                    Text(route.path, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                }
            }
        }
        val children = model.graphs.filter { it.parent == graph.id }
        if (children.isNotEmpty()) {
            SectionHeading("Graphs", children.size.toString())
            children.forEach { child ->
                TextButton(onClick = { onSelectGraph(child.id) }) {
                    Text(child.id, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}
