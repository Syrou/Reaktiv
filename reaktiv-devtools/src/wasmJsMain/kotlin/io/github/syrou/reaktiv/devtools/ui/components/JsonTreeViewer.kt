package io.github.syrou.reaktiv.devtools.ui.components

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString.Builder
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import io.github.syrou.reaktiv.devtools.ui.LocalDiffColors
import io.github.syrou.reaktiv.devtools.ui.LocalSyntaxColors
import kotlinx.serialization.json.*

/**
 * Interactive JSON tree viewer with syntax highlighting and expand/collapse.
 */
@Composable
internal fun JsonTreeViewer(
    jsonString: String,
    previousJsonString: String? = null,
    searchQuery: String = "",
    showDiff: Boolean = false,
    modifier: Modifier = Modifier,
    header: (LazyListScope.() -> Unit)? = null
) {
    val jsonElement = remember(jsonString) {
        try {
            Json.parseToJsonElement(jsonString)
        } catch (e: Exception) {
            JsonPrimitive("Error parsing JSON: ${e.message}")
        }
    }

    val previousJsonElement = remember(previousJsonString) {
        previousJsonString?.let {
            try {
                Json.parseToJsonElement(it)
            } catch (e: Exception) {
                null
            }
        }
    }

    val expandedPaths = remember { mutableStateMapOf<String, Boolean>() }
    var expandAll by remember { mutableStateOf<Boolean?>(null) }
    val listState = rememberLazyListState()

    val nodes = buildTreeNodes(
        element = jsonElement,
        path = "",
        expandedPaths = expandedPaths,
        expandAll = expandAll,
        searchQuery = searchQuery,
        previousElement = if (showDiff) previousJsonElement else null,
        showDiffOnly = showDiff
    )

    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(end = 12.dp)
        ) {
            header?.invoke(this)

            item(key = "tree-toolbar") {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Caption("${nodes.size} rows", modifier = Modifier.weight(1f))
                    TextButton(
                        onClick = {
                            expandedPaths.clear()
                            expandAll = true
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                    ) {
                        Text("Expand all", style = MaterialTheme.typography.labelSmall)
                    }
                    TextButton(
                        onClick = {
                            expandedPaths.clear()
                            expandAll = false
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                    ) {
                        Text("Collapse all", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            items(nodes) { node ->
                JsonTreeNode(
                    node = node,
                    onToggleExpand = { path ->
                        expandedPaths[path] = !node.isExpanded
                    }
                )
            }
        }

        VerticalScrollbar(
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
            adapter = rememberScrollbarAdapter(listState)
        )
    }
}

private data class TreeNode(
    val path: String,
    val key: String?,
    val isArrayElement: Boolean = false,
    val value: JsonElement,
    val previousValue: JsonElement? = null,
    val depth: Int,
    val isExpanded: Boolean,
    val matchesSearch: Boolean,
    val diffStatus: DiffStatus = DiffStatus.UNCHANGED
)

private enum class DiffStatus {
    UNCHANGED,
    ADDED,
    MODIFIED,
    REMOVED
}

private val prettyJson = Json { prettyPrint = true }

private fun TreeNode.copyablePath(): String? = path.ifEmpty { null }

private const val PREVIEW_ENTRIES = 3
private const val PREVIEW_VALUE_CHARS = 18

private fun previewOf(element: JsonElement): String {
    val parts = when (element) {
        is JsonObject -> element.entries.take(PREVIEW_ENTRIES).map { (k, v) -> "$k: ${previewValue(v)}" }
        is JsonArray -> element.take(PREVIEW_ENTRIES).map { previewValue(it) }
        else -> return ""
    }
    val size = if (element is JsonArray) element.size else (element as JsonObject).size
    if (size == 0) return ""
    val shown = parts.joinToString(", ")
    return if (size > PREVIEW_ENTRIES) " $shown, +${size - PREVIEW_ENTRIES} " else " $shown "
}

private fun previewValue(element: JsonElement): String = when (element) {
    is JsonObject -> if (element.isEmpty()) "{}" else "{…}"
    is JsonArray -> if (element.isEmpty()) "[]" else "[${element.size}]"
    is JsonPrimitive -> {
        val raw = if (element.isString) "\"${element.content}\"" else element.content
        if (raw.length > PREVIEW_VALUE_CHARS) raw.take(PREVIEW_VALUE_CHARS - 1) + "…" else raw
    }
    else -> element.toString()
}

private fun buildTreeNodes(
    element: JsonElement,
    path: String,
    expandedPaths: Map<String, Boolean>,
    expandAll: Boolean?,
    searchQuery: String,
    key: String? = null,
    isArrayElement: Boolean = false,
    depth: Int = 0,
    parentMatches: Boolean = false,
    previousElement: JsonElement? = null,
    showDiffOnly: Boolean = false
): List<TreeNode> {
    val nodes = mutableListOf<TreeNode>()
    val currentPath = when {
        key == null -> path
        isArrayElement -> "$path[$key]"
        path.isEmpty() -> key
        else -> "$path.$key"
    }
    val isExpanded = expandedPaths[currentPath] ?: expandAll ?: (depth < 2)
    val matchesSearch = searchQuery.isEmpty() ||
        key?.contains(searchQuery, ignoreCase = true) == true ||
        (element is JsonPrimitive && element.content.contains(searchQuery, ignoreCase = true))

    val shouldInclude = searchQuery.isEmpty() || matchesSearch || parentMatches

    val diffStatus = when {
        previousElement == null && !showDiffOnly -> DiffStatus.UNCHANGED
        previousElement == null && showDiffOnly -> DiffStatus.ADDED
        element != previousElement -> DiffStatus.MODIFIED
        else -> DiffStatus.UNCHANGED
    }

    val shouldIncludeInDiff = !showDiffOnly || diffStatus != DiffStatus.UNCHANGED

    when (element) {
        is JsonObject -> {
            val previousObject = previousElement as? JsonObject
            val childNodes = mutableListOf<TreeNode>()
            if (isExpanded) {
                element.forEach { (childKey, childValue) ->
                    val prevChildValue = previousObject?.get(childKey)
                    childNodes.addAll(
                        buildTreeNodes(
                            childValue,
                            currentPath,
                            expandedPaths,
                            expandAll,
                            searchQuery,
                            childKey,
                            false,
                            depth + 1,
                            matchesSearch || parentMatches,
                            prevChildValue,
                            showDiffOnly
                        )
                    )
                }
            }

            val hasMatchingDescendants = childNodes.isNotEmpty()
            if ((shouldInclude || hasMatchingDescendants) && shouldIncludeInDiff) {
                nodes.add(TreeNode(currentPath, key, isArrayElement, element, previousElement, depth, isExpanded, matchesSearch, diffStatus))
                nodes.addAll(childNodes)
            }
        }
        is JsonArray -> {
            val previousArray = previousElement as? JsonArray
            val childNodes = mutableListOf<TreeNode>()
            if (isExpanded) {
                element.forEachIndexed { index, childValue ->
                    val prevChildValue = previousArray?.getOrNull(index)
                    childNodes.addAll(
                        buildTreeNodes(
                            childValue,
                            currentPath,
                            expandedPaths,
                            expandAll,
                            searchQuery,
                            index.toString(),
                            true,
                            depth + 1,
                            matchesSearch || parentMatches,
                            prevChildValue,
                            showDiffOnly
                        )
                    )
                }
            }

            val hasMatchingDescendants = childNodes.isNotEmpty()
            if ((shouldInclude || hasMatchingDescendants) && shouldIncludeInDiff) {
                nodes.add(TreeNode(currentPath, key, isArrayElement, element, previousElement, depth, isExpanded, matchesSearch, diffStatus))
                nodes.addAll(childNodes)
            }
        }
        else -> {
            if (shouldInclude && shouldIncludeInDiff) {
                nodes.add(TreeNode(currentPath, key, isArrayElement, element, previousElement, depth, false, matchesSearch, diffStatus))
            }
        }
    }

    return nodes
}

@Composable
private fun JsonTreeNode(
    node: TreeNode,
    onToggleExpand: (String) -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val diffColors = LocalDiffColors.current
    val syntaxColors = LocalSyntaxColors.current
    val backgroundColor = when (node.diffStatus) {
        DiffStatus.ADDED -> diffColors.addedContainer.copy(alpha = 0.6f)
        DiffStatus.MODIFIED -> diffColors.modifiedContainer.copy(alpha = 0.6f)
        DiffStatus.REMOVED -> diffColors.removedContainer.copy(alpha = 0.6f)
        DiffStatus.UNCHANGED -> Color.Transparent
    }

    // Dedicated syntax highlighting colors
    val keyColor = syntaxColors.key
    val stringColor = syntaxColors.string
    val booleanColor = syntaxColors.boolean
    val numberColor = syntaxColors.number
    val nullColor = syntaxColors.nullValue
    val bracketColor = syntaxColors.bracket
    val oldValueColor = syntaxColors.oldValue

    val diffIndicator = when (node.diffStatus) {
        DiffStatus.ADDED -> "+ "
        DiffStatus.MODIFIED -> "~ "
        DiffStatus.REMOVED -> "- "
        DiffStatus.UNCHANGED -> ""
    }

    val isPrimitive = node.value is JsonPrimitive

    val valueString = when (val v = node.value) {
        is JsonPrimitive -> if (v.isString) v.content else v.content
        else -> null
    }

    val expandable = node.value is JsonObject || node.value is JsonArray

    TreeRowShell(
        depth = node.depth,
        background = backgroundColor,
        onClick = if (expandable) ({ onToggleExpand(node.path) }) else null,
        copyActions = treeRowCopyActions(
            path = node.copyablePath(),
            key = node.key,
            value = valueString,
            subtreeJson = if (isPrimitive) null else {
                { prettyJson.encodeToString(JsonElement.serializer(), node.value) }
            }
        )
    ) {
        if (expandable) {
            Icon(
                imageVector = if (node.isExpanded) {
                    Icons.Default.KeyboardArrowDown
                } else {
                    Icons.Default.KeyboardArrowRight
                },
                contentDescription = if (node.isExpanded) "Collapse" else "Expand",
                modifier = Modifier.size(14.dp),
                tint = colors.onSurfaceVariant
            )
        } else {
            Spacer(modifier = Modifier.width(14.dp))
        }

        if (node.diffStatus == DiffStatus.MODIFIED && node.value is JsonPrimitive && node.previousValue is JsonPrimitive) {
            Text(
                text = buildAnnotatedString {
                    if (diffIndicator.isNotEmpty()) {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = colors.onSurface)) {
                            append(diffIndicator)
                        }
                    }

                    if (node.key != null) {
                        withStyle(
                            SpanStyle(
                                color = if (node.isArrayElement) colors.onSurfaceVariant else keyColor,
                                fontWeight = if (node.isArrayElement) FontWeight.Normal else FontWeight.Medium
                            )
                        ) {
                            append(node.key)
                        }
                        withStyle(SpanStyle(color = colors.onSurfaceVariant)) {
                            append(": ")
                        }
                    }

                    withStyle(SpanStyle(color = oldValueColor)) {
                        renderPrimitiveValue(node.previousValue, stringColor, booleanColor, numberColor, nullColor)
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace
            )

            Icon(
                imageVector = Icons.Default.ArrowForward,
                contentDescription = "changed to",
                modifier = Modifier.size(12.dp).padding(horizontal = 4.dp),
                tint = colors.onSurface
            )

            Text(
                text = buildAnnotatedString {
                    renderPrimitiveValue(node.value, stringColor, booleanColor, numberColor, nullColor)
                },
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace
            )
        } else {
            Text(
                text = buildAnnotatedString {
                    if (diffIndicator.isNotEmpty()) {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = colors.onSurface)) {
                            append(diffIndicator)
                        }
                    }

                    if (node.key != null) {
                        withStyle(
                            SpanStyle(
                                color = if (node.isArrayElement) colors.onSurfaceVariant else keyColor,
                                fontWeight = if (node.isArrayElement) FontWeight.Normal else FontWeight.Medium
                            )
                        ) {
                            append(node.key)
                        }
                        withStyle(SpanStyle(color = colors.onSurfaceVariant)) {
                            append(": ")
                        }
                    }

                    when (val value = node.value) {
                        is JsonObject, is JsonArray -> {
                            val array = value is JsonArray
                            val size = if (array) (value as JsonArray).size else (value as JsonObject).size
                            withStyle(SpanStyle(color = bracketColor)) {
                                append(if (array) "[" else "{")
                            }
                            if (node.isExpanded) {
                                withStyle(SpanStyle(color = colors.onSurfaceVariant)) {
                                    append("  $size")
                                }
                            } else {
                                withStyle(SpanStyle(color = colors.onSurfaceVariant)) {
                                    append(previewOf(value))
                                }
                                withStyle(SpanStyle(color = bracketColor)) {
                                    append(if (array) "]" else "}")
                                }
                            }
                        }
                        is JsonPrimitive -> {
                            renderPrimitiveValue(value, stringColor, booleanColor, numberColor, nullColor)
                        }
                        else -> {
                            withStyle(SpanStyle(color = colors.onSurface)) {
                                append(value.toString())
                            }
                        }
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

private fun Builder.renderPrimitiveValue(
    value: JsonPrimitive,
    stringColor: Color,
    booleanColor: Color,
    numberColor: Color,
    nullColor: Color
) {
    when {
        value.isString -> {
            withStyle(SpanStyle(color = stringColor)) {
                append("\"${value.content}\"")
            }
        }
        value.content == "true" || value.content == "false" -> {
            withStyle(SpanStyle(color = booleanColor)) {
                append(value.content)
            }
        }
        value.content == "null" -> {
            withStyle(SpanStyle(color = nullColor)) {
                append("null")
            }
        }
        else -> {
            withStyle(SpanStyle(color = numberColor)) {
                append(value.content)
            }
        }
    }
}


