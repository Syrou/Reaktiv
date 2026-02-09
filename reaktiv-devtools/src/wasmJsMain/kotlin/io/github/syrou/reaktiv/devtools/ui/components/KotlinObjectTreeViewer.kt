package io.github.syrou.reaktiv.devtools.ui.components

import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

data class TreeRow(
    val indent: Int,
    val segments: List<TextSegment>
)

data class TextSegment(
    val text: String,
    val color: SegmentColor
)

enum class SegmentColor {
    CLASS_NAME, KEY, SEPARATOR, VALUE, BRACKET
}

/**
 * Displays a Kotlin toString() output as an indented tree.
 *
 * Uses an iterative tokenizer to avoid recursion and WASM stack overflow.
 */
@Composable
fun KotlinObjectTreeViewer(
    text: String,
    modifier: Modifier = Modifier
) {
    val rows = remember(text) { KotlinTokenizer.tokenize(text) }
    val listState = rememberLazyListState()
    val horizontalScrollState = rememberScrollState()

    Box(modifier = modifier.fillMaxWidth()) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .padding(end = 12.dp, bottom = 12.dp)
                .horizontalScroll(horizontalScrollState)
        ) {
            items(rows) { row ->
                TreeRowItem(row)
            }
        }

        VerticalScrollbar(
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
            adapter = rememberScrollbarAdapter(listState)
        )

        HorizontalScrollbar(
            modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(end = 12.dp),
            adapter = rememberScrollbarAdapter(horizontalScrollState)
        )
    }
}

@Composable
private fun TreeRowItem(row: TreeRow) {
    var showCopyMenu by remember { mutableStateOf(false) }

    val keyText = row.segments.firstOrNull { it.color == SegmentColor.KEY }?.text
    val valueText = row.segments.firstOrNull { it.color == SegmentColor.VALUE }?.text
    val hasCopyableContent = keyText != null || valueText != null

    Box {
        Row(
            modifier = Modifier
                .padding(start = (row.indent * 16).dp, top = 1.dp, bottom = 1.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            row.segments.forEach { segment ->
                Text(
                    text = segment.text,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = when (segment.color) {
                        SegmentColor.CLASS_NAME -> MaterialTheme.colorScheme.tertiary
                        SegmentColor.KEY -> MaterialTheme.colorScheme.primary
                        SegmentColor.SEPARATOR -> MaterialTheme.colorScheme.onSurfaceVariant
                        SegmentColor.VALUE -> MaterialTheme.colorScheme.onSurface
                        SegmentColor.BRACKET -> MaterialTheme.colorScheme.secondary
                    }
                )
            }

            if (hasCopyableContent) {
                IconButton(
                    onClick = { showCopyMenu = true },
                    modifier = Modifier.size(16.dp).padding(start = 4.dp)
                ) {
                    Icon(
                        Icons.Default.ContentCopy,
                        contentDescription = "Copy",
                        modifier = Modifier.size(12.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                }
            }
        }

        if (showCopyMenu) {
            DropdownMenu(
                expanded = true,
                onDismissRequest = { showCopyMenu = false }
            ) {
                if (keyText != null) {
                    DropdownMenuItem(
                        text = { Text("Copy key") },
                        onClick = {
                            copyToClipboard(keyText)
                            showCopyMenu = false
                        }
                    )
                }
                if (valueText != null) {
                    DropdownMenuItem(
                        text = { Text("Copy value") },
                        onClick = {
                            copyToClipboard(valueText)
                            showCopyMenu = false
                        }
                    )
                }
                if (keyText != null && valueText != null) {
                    DropdownMenuItem(
                        text = { Text("Copy both") },
                        onClick = {
                            copyToClipboard("$keyText = $valueText")
                            showCopyMenu = false
                        }
                    )
                }
                val fullLine = row.segments.joinToString("") { it.text }
                DropdownMenuItem(
                    text = { Text("Copy line") },
                    onClick = {
                        copyToClipboard(fullLine)
                        showCopyMenu = false
                    }
                )
            }
        }
    }
}

private fun copyToClipboard(text: String) {
    js("navigator.clipboard.writeText(text)")
}

/**
 * Iterative tokenizer that converts Kotlin toString() output into flat display rows.
 * Uses a class to avoid closure overhead that can cause WASM compiler OOM.
 */
object KotlinTokenizer {

    fun tokenize(input: String): List<TreeRow> {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) {
            return listOf(TreeRow(0, listOf(TextSegment("", SegmentColor.VALUE))))
        }

        val rows = mutableListOf<TreeRow>()
        val state = TokenizerState(trimmed)

        while (state.pos < state.len) {
            state.skipWs()
            if (state.pos >= state.len) break

            val ch = state.input[state.pos]

            when {
                state.isObjectStart() -> {
                    val className = state.readIdent()
                    state.pos++ // skip (
                    rows.add(TreeRow(state.indent, listOf(TextSegment("$className(", SegmentColor.CLASS_NAME))))
                    state.indent++
                    state.contextStack.addLast('(')
                }

                ch == '[' -> {
                    state.pos++
                    rows.add(TreeRow(state.indent, listOf(TextSegment("[", SegmentColor.BRACKET))))
                    state.indent++
                    state.contextStack.addLast('[')
                }

                ch == ')' || ch == ']' -> {
                    state.indent = maxOf(0, state.indent - 1)
                    val color = if (ch == ')') SegmentColor.CLASS_NAME else SegmentColor.BRACKET
                    state.pos++
                    state.skipWs()
                    if (state.pos < state.len && state.input[state.pos] == ',') state.pos++
                    rows.add(TreeRow(state.indent, listOf(TextSegment(ch.toString(), color))))
                    if (state.contextStack.isNotEmpty()) state.contextStack.removeLast()
                }

                state.contextStack.isNotEmpty() && state.contextStack.last() == '(' && ch.isLetterOrDigit() -> {
                    val savedPos = state.pos
                    val key = state.readIdent()
                    state.skipWs()
                    if (state.pos < state.len && state.input[state.pos] == '=') {
                        state.pos++ // skip =
                        state.skipWs()
                        if (state.pos < state.len && (state.isObjectStart() || state.input[state.pos] == '[')) {
                            rows.add(TreeRow(state.indent, listOf(
                                TextSegment(key, SegmentColor.KEY),
                                TextSegment(" = ", SegmentColor.SEPARATOR)
                            )))
                        } else {
                            val value = state.readRaw()
                            state.skipWs()
                            if (state.pos < state.len && state.input[state.pos] == ',') state.pos++
                            rows.add(TreeRow(state.indent, listOf(
                                TextSegment(key, SegmentColor.KEY),
                                TextSegment(" = ", SegmentColor.SEPARATOR),
                                TextSegment(value, SegmentColor.VALUE)
                            )))
                        }
                    } else {
                        state.pos = savedPos
                        val value = state.readRaw()
                        state.skipWs()
                        if (state.pos < state.len && state.input[state.pos] == ',') state.pos++
                        rows.add(TreeRow(state.indent, listOf(TextSegment(value, SegmentColor.VALUE))))
                    }
                }

                else -> {
                    if (state.isObjectStart()) continue
                    val value = state.readRaw()
                    if (value.isNotEmpty()) {
                        state.skipWs()
                        if (state.pos < state.len && state.input[state.pos] == ',') state.pos++
                        rows.add(TreeRow(state.indent, listOf(TextSegment(value, SegmentColor.VALUE))))
                    } else {
                        state.pos++
                    }
                }
            }
        }

        if (rows.isEmpty()) {
            rows.add(TreeRow(0, listOf(TextSegment(trimmed, SegmentColor.VALUE))))
        }

        return rows
    }
}

private class TokenizerState(val input: String) {
    var pos = 0
    val len = input.length
    var indent = 0
    val contextStack = ArrayDeque<Char>()

    fun skipWs() {
        while (pos < len && input[pos].isWhitespace()) pos++
    }

    fun readIdent(): String {
        val start = pos
        while (pos < len && (input[pos].isLetterOrDigit() || input[pos] == '.' || input[pos] == '$' || input[pos] == '_')) {
            pos++
        }
        return input.substring(start, pos)
    }

    fun readRaw(): String {
        val start = pos
        var depth = 0
        while (pos < len) {
            when (input[pos]) {
                '(', '[' -> depth++
                ')', ']' -> {
                    if (depth == 0) break
                    depth--
                }
                ',' -> if (depth == 0) break
                '=' -> if (depth == 0) break
            }
            pos++
        }
        return input.substring(start, pos).trim()
    }

    fun isObjectStart(): Boolean {
        if (pos >= len || !input[pos].isLetter()) return false
        var j = pos
        while (j < len && (input[j].isLetterOrDigit() || input[j] == '.' || input[j] == '$' || input[j] == '_')) {
            j++
        }
        return j < len && input[j] == '('
    }
}
