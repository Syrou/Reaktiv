package io.github.syrou.reaktiv.devtools.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
fun JsonTreeViewer(
    jsonString: String,
    previousJsonString: String? = null,
    searchQuery: String = "",
    showDiff: Boolean = false,
    modifier: Modifier = Modifier
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

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        items(buildTreeNodes(
            element = jsonElement,
            path = "",
            expandedPaths = expandedPaths,
            searchQuery = searchQuery,
            previousElement = if (showDiff) previousJsonElement else null,
            showDiffOnly = showDiff
        )) { node ->
            JsonTreeNode(
                node = node,
                onToggleExpand = { path ->
                    expandedPaths[path] = !(expandedPaths[path] ?: false)
                }
            )
        }
    }
}

private data class TreeNode(
    val path: String,
    val key: String?,
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

private fun buildTreeNodes(
    element: JsonElement,
    path: String,
    expandedPaths: Map<String, Boolean>,
    searchQuery: String,
    key: String? = null,
    depth: Int = 0,
    parentMatches: Boolean = false,
    previousElement: JsonElement? = null,
    showDiffOnly: Boolean = false
): List<TreeNode> {
    val nodes = mutableListOf<TreeNode>()
    val currentPath = if (key != null) "$path.$key" else path
    val isExpanded = expandedPaths[currentPath] ?: (depth < 2)
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
                            searchQuery,
                            childKey,
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
                nodes.add(TreeNode(currentPath, key, element, previousElement, depth, isExpanded, matchesSearch, diffStatus))
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
                            searchQuery,
                            "[$index]",
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
                nodes.add(TreeNode(currentPath, key, element, previousElement, depth, isExpanded, matchesSearch, diffStatus))
                nodes.addAll(childNodes)
            }
        }
        else -> {
            if (shouldInclude && shouldIncludeInDiff) {
                nodes.add(TreeNode(currentPath, key, element, previousElement, depth, false, matchesSearch, diffStatus))
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
    val indentPadding = (node.depth * 16).dp

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

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(backgroundColor)
            .clickable(enabled = node.value is JsonObject || node.value is JsonArray) {
                onToggleExpand(node.path)
            }
            .padding(start = indentPadding, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        when (node.value) {
            is JsonObject -> {
                Icon(
                    imageVector = if (node.isExpanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowRight,
                    contentDescription = if (node.isExpanded) "Collapse" else "Expand",
                    modifier = Modifier.size(16.dp),
                    tint = colors.onSurface
                )
            }
            is JsonArray -> {
                Icon(
                    imageVector = if (node.isExpanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowRight,
                    contentDescription = if (node.isExpanded) "Collapse" else "Expand",
                    modifier = Modifier.size(16.dp),
                    tint = colors.onSurface
                )
            }
            else -> {
                Spacer(modifier = Modifier.width(16.dp))
            }
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
                        withStyle(SpanStyle(color = keyColor, fontWeight = FontWeight.Bold)) {
                            append("\"${node.key}\"")
                        }
                        withStyle(SpanStyle(color = colors.onSurface)) {
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
                        withStyle(SpanStyle(color = keyColor, fontWeight = FontWeight.Bold)) {
                            append("\"${node.key}\"")
                        }
                        withStyle(SpanStyle(color = colors.onSurface)) {
                            append(": ")
                        }
                    }

                    when (val value = node.value) {
                        is JsonObject -> {
                            withStyle(SpanStyle(color = bracketColor)) {
                                append("{ ")
                                if (!node.isExpanded) {
                                    append("${value.size} ${if (value.size == 1) "property" else "properties"}")
                                }
                                append(" }")
                            }
                        }
                        is JsonArray -> {
                            withStyle(SpanStyle(color = bracketColor)) {
                                append("[ ")
                                if (!node.isExpanded) {
                                    append("${value.size} ${if (value.size == 1) "item" else "items"}")
                                }
                                append(" ]")
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
