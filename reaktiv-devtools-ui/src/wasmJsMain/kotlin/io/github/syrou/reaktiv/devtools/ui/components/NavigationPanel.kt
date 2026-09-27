package io.github.syrou.reaktiv.devtools.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.syrou.reaktiv.devtools.protocol.NavigationAttempt
import io.github.syrou.reaktiv.devtools.protocol.NavigationEntrySnapshot
import io.github.syrou.reaktiv.devtools.protocol.NavigationSnapshot
import io.github.syrou.reaktiv.devtools.protocol.buildNavigationLog
import io.github.syrou.reaktiv.devtools.protocol.parseNavigationState
import io.github.syrou.reaktiv.devtools.ui.LogicMethodEvent
import io.github.syrou.reaktiv.devtools.ui.LogicTrace
import io.github.syrou.reaktiv.devtools.ui.Reconstruction
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.TextButton
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import io.github.syrou.reaktiv.devtools.ui.DevToolsUiAction
import io.github.syrou.reaktiv.devtools.ui.DevToolsUiState
import io.github.syrou.reaktiv.devtools.ui.NavigationView
import io.github.syrou.reaktiv.devtools.ui.Overlay
import io.github.syrou.reaktiv.devtools.ui.navigationPositionIndex
import io.github.syrou.reaktiv.devtools.ui.navmap.MapMetrics
import io.github.syrou.reaktiv.devtools.ui.navmap.NAVIGATION_LINKS_EXTENSION
import io.github.syrou.reaktiv.devtools.ui.navmap.layoutLinkMap
import io.github.syrou.reaktiv.devtools.ui.navmap.parseLinkMap
import io.github.syrou.reaktiv.devtools.ui.navmap.search
import io.github.syrou.reaktiv.devtools.ui.selectedGraphId
import io.github.syrou.reaktiv.devtools.ui.selectedRoutePath

@Composable
internal fun NavigationPanel(
    state: DevToolsUiState,
    dispatch: (DevToolsUiAction) -> Unit,
    searchField: @Composable () -> Unit,
    onExportMap: () -> Unit
) {
    val model = remember(state.extensions) { parseLinkMap(state.extensions[NAVIGATION_LINKS_EXTENSION]) }
    val snapshot = rememberNavigationSnapshot(state)
    val showMap = state.navigationView == NavigationView.MAP

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            NavigationView.entries.forEach { view ->
                FilterChip(
                    selected = view == state.navigationView,
                    onClick = { dispatch(DevToolsUiAction.SetNavigationView(view)) },
                    label = { Text(view.label, style = MaterialTheme.typography.labelSmall) }
                )
            }
            if (showMap && model != null) {
                searchField()
                Spacer(modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    val foldable = model.graphs
                        .filter { graph -> graph.parent != null && model.graphs.any { it.parent == graph.id } }
                        .map { it.id }
                        .toSet()
                    dispatch(
                        DevToolsUiAction.SetCollapsedGraphs(if (state.collapsedGraphs.isEmpty()) foldable else emptySet())
                    )
                }) {
                    Text(
                        if (state.collapsedGraphs.isEmpty()) "Fold all" else "Unfold all",
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        softWrap = false
                    )
                }
                AssistChip(
                    onClick = { dispatch(DevToolsUiAction.SetOverlay(Overlay.AppLinks)) },
                    label = { Text("App links", style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false) }
                )
                AssistChip(
                    onClick = onExportMap,
                    label = { Text("Export JSON", style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false) }
                )
            }
        }
        if (showMap) {
            if (model == null) {
                EmptyState(
                    title = "No navigation map from this app yet",
                    detail = "Install NavigationLinks() in the app's tooling module, next to DevToolsService. " +
                        "Devices and DevTools servers older than this UI do not send the map.",
                    modifier = Modifier.fillMaxWidth().weight(1f)
                )
            } else {
                val search = remember(model, state.searchQuery) { model.search(state.searchQuery) }
                val metrics = rememberMapMetrics()
                val layout = remember(model, state.collapsedGraphs, search.expand, metrics) {
                    layoutLinkMap(model, state.collapsedGraphs, metrics, search.expand)
                }
                NavigationMap(
                    view = state.view,
                    onViewChange = { dispatch(DevToolsUiAction.SetViewChoices(it)) },
                    model = model,
                    summary = "${model.routes.size} routes, ${model.graphs.size} graphs, ${model.aliases.size} aliases",
                    layoutKey = model,
                    layout = layout,
                    search = search,
                    snapshot = snapshot,
                    selectedRoute = state.selectedRoutePath,
                    selectedGraph = state.selectedGraphId,
                    onSelectRoute = { dispatch(DevToolsUiAction.SelectRoute(it)) },
                    onSelectGraph = { dispatch(DevToolsUiAction.SelectGraph(it)) },
                    onToggleGraph = { dispatch(DevToolsUiAction.ToggleGraphCollapsed(it)) },
                    modifier = Modifier.fillMaxWidth().weight(1f)
                )
            }
        } else {
            NavigationLiveView(
                state = state,
                snapshot = snapshot,
                onSelectRoute = { path -> dispatch(DevToolsUiAction.SelectRoute(path)) },
                modifier = Modifier.fillMaxWidth().weight(1f)
            )
        }
    }
}

@Composable
private fun rememberMapMetrics(): MapMetrics {
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    return remember(density) {
        val sample = textMeasurer.measure(
            "0000000000",
            TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        ).size.width / 10f
        with(density) {
            MapMetrics(
                charWidth = sample,
                rowHeight = 22.dp.toPx(),
                headerHeight = 40.dp.toPx(),
                cardPadding = 10.dp.toPx(),
                tagWidth = 72.dp.toPx(),
                columnGap = 96.dp.toPx(),
                siblingGap = 18.dp.toPx(),
                bandPadding = 12.dp.toPx(),
                minCardWidth = 180.dp.toPx(),
                maxCardWidth = 400.dp.toPx()
            )
        }
    }
}

@Composable
private fun rememberNavigationSnapshot(state: DevToolsUiState): NavigationSnapshot? {
    val index = state.navigationPositionIndex
    return remember(state.actionStateHistory, index, state.initialStateJson) {
        val stateJson = when (index) {
            null -> state.initialStateJson
            else -> Reconstruction.stateAt(state.initialStateJson, state.actionStateHistory, index)
        }
        parseNavigationState(stateJson)
    }
}

/**
 * Where navigation is, and how it got there.
 *
 * The back stack is read out of the reconstructed state at the selected action, so stepping
 * through the session steps through navigation. The log underneath pairs every navigate call and
 * guard evaluation with its verdict, which is the part that explains a redirect nobody asked for.
 */
@Composable
private fun NavigationLiveView(
    state: DevToolsUiState,
    snapshot: NavigationSnapshot?,
    onSelectRoute: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val log = remember(state.logicMethodEvents) {
        val trace = LogicTrace.of(state.logicMethodEvents)
        buildNavigationLog(starts = trace.started, completions = trace.completed)
    }

    Column(
        modifier = modifier.padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (snapshot == null) {
            EmptyState(
                title = "This app has no navigation module",
                detail = "The back stack and guard log appear for any store with " +
                    "reaktiv-navigation registered.",
                modifier = Modifier.fillMaxWidth().weight(1f)
            )
        } else {
            PositionCard(snapshot)
            BackStackList(snapshot, onSelectRoute, modifier = Modifier.weight(1f))
        }

        NavigationLogList(log, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun PositionCard(snapshot: NavigationSnapshot) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Text(
                text = snapshot.currentPath ?: "unresolved",
                style = MaterialTheme.typography.titleSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.primary
            )
            if (snapshot.graphChain.isNotEmpty()) {
                Text(
                    text = "In graph: ${snapshot.graphChain.joinToString(" / ")}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(top = 6.dp)
            ) {
                if (snapshot.isBootstrapping) StateChip("bootstrapping", MaterialTheme.colorScheme.tertiary)
                if (snapshot.isEvaluating) StateChip("evaluating", MaterialTheme.colorScheme.tertiary)
                if (snapshot.isCurrentModal) StateChip("modal", MaterialTheme.colorScheme.secondary)
                if (snapshot.modalContextPaths.isNotEmpty()) {
                    StateChip(
                        "${snapshot.modalContextPaths.size} modal context",
                        MaterialTheme.colorScheme.secondary
                    )
                }
            }
        }
    }
}

@Composable
private fun StateChip(label: String, color: Color) {
    Surface(color = color.copy(alpha = 0.18f), shape = RoundedCornerShape(3.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun BackStackList(snapshot: NavigationSnapshot, onSelectRoute: (String) -> Unit, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    Column(modifier = modifier) {
        SectionHeading("Back stack", "${snapshot.backStack.size}")
        Box(modifier = Modifier.fillMaxSize()) {
            val depth = snapshot.backStack.size - 1
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                itemsIndexed(snapshot.backStack.asReversed()) { indexFromTop, entry ->
                    BackStackRow(
                        entry = entry,
                        depth = depth - indexFromTop,
                        isCurrent = indexFromTop == 0,
                        onClick = { onSelectRoute(entry.path) }
                    )
                }
            }
        }
    }
}

@Composable
private fun BackStackRow(entry: NavigationEntrySnapshot, depth: Int, isCurrent: Boolean, onClick: () -> Unit) {
    val accent = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(color = accent, modifier = Modifier.width(3.dp)) { Box(Modifier.padding(vertical = 8.dp)) }
        Column(modifier = Modifier.padding(start = 8.dp)) {
            Text(
                text = entry.route,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                color = if (isCurrent) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "$depth  ${entry.path}" +
                    if (entry.params.isNotEmpty()) "  ${entry.params}" else "",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun NavigationLogList(log: List<NavigationAttempt>, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    Column(modifier = modifier) {
        SectionHeading("Navigation log", "${log.size}")
        if (log.isEmpty()) {
            EmptyState(
                title = "No navigation attempts captured",
                detail = "Navigate calls and guard verdicts are recorded by the tracing compiler " +
                    "plugin. Set reaktivTracing.enabled for this build and reconnect."
            )
            return@Column
        }
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            items(log.asReversed()) { attempt -> NavigationLogRow(attempt) }
        }
    }
}

@Composable
private fun NavigationLogRow(attempt: NavigationAttempt) {
    val outcomeColor = when {
        attempt.outcome == null -> MaterialTheme.colorScheme.onSurfaceVariant
        attempt.diverted -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.primary
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.Top
    ) {
        Surface(color = outcomeColor, modifier = Modifier.width(3.dp)) {
            Box(Modifier.padding(vertical = 8.dp))
        }
        Column(modifier = Modifier.padding(start = 8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = attempt.outcome ?: "running",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    color = outcomeColor
                )
                if (attempt.isGuard) StateChip("guard", MaterialTheme.colorScheme.tertiary)
            }
            Text(
                text = "${attempt.name}  ->  ${attempt.target}",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            attempt.durationMs?.let {
                Text(
                    text = "${it}ms",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
