package io.github.syrou.reaktiv.devtools.ui.components

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

internal data class TreeRow(
    val indent: Int,
    val segments: List<TextSegment>,
    val path: String? = null
)

internal data class TextSegment(
    val text: String,
    val color: SegmentColor
)

internal enum class SegmentColor {
    CLASS_NAME, KEY, SEPARATOR, VALUE, BRACKET
}

/**
 * Displays a Kotlin toString() output as an indented tree.
 *
 * Uses an iterative tokenizer to avoid recursion and WASM stack overflow.
 */
@Composable
internal fun KotlinObjectTreeViewer(
    text: String,
    modifier: Modifier = Modifier,
    header: (LazyListScope.() -> Unit)? = null
) {
    val rows = remember(text) { KotlinTokenizer.tokenize(text) }
    val listState = rememberLazyListState()

    Box(modifier = modifier.fillMaxWidth()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth().padding(end = 12.dp)
        ) {
            header?.invoke(this)
            items(rows) { row ->
                TreeRowItem(row)
            }
        }

        VerticalScrollbar(
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
            adapter = rememberScrollbarAdapter(listState)
        )
    }
}

@Composable
private fun TreeRowItem(row: TreeRow) {
    val keyText = row.segments.firstOrNull { it.color == SegmentColor.KEY }?.text
    val valueText = row.segments.firstOrNull { it.color == SegmentColor.VALUE }?.text

    TreeRowShell(
        depth = row.indent,
        copyActions = treeRowCopyActions(
            path = row.path,
            key = keyText,
            value = valueText,
            wholeLine = { row.segments.joinToString("") { it.text } }
        )
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
    }
}



/**
 * Iterative tokenizer that converts Kotlin toString() output into flat display rows.
 * Uses a class to avoid closure overhead that can cause WASM compiler OOM.
 */
internal object KotlinTokenizer {

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
                    val nodePath = state.enterContainer(className)
                    rows.add(TreeRow(state.indent, listOf(TextSegment("$className(", SegmentColor.CLASS_NAME)), nodePath))
                    state.indent++
                    state.contextStack.addLast('(')
                    state.frames.add(PathFrame(nodePath.orEmpty(), isList = false))
                }

                ch == '[' -> {
                    state.pos++
                    val nodePath = state.enterContainer(null)
                    rows.add(TreeRow(state.indent, listOf(TextSegment("[", SegmentColor.BRACKET)), nodePath))
                    state.indent++
                    state.contextStack.addLast('[')
                    state.frames.add(PathFrame(nodePath.orEmpty(), isList = true))
                }

                ch == ')' || ch == ']' -> {
                    state.indent = maxOf(0, state.indent - 1)
                    val color = if (ch == ')') SegmentColor.CLASS_NAME else SegmentColor.BRACKET
                    state.pos++
                    state.skipWs()
                    if (state.pos < state.len && state.input[state.pos] == ',') state.pos++
                    rows.add(TreeRow(state.indent, listOf(TextSegment(ch.toString(), color))))
                    if (state.contextStack.isNotEmpty()) state.contextStack.removeLast()
                    if (state.frames.isNotEmpty()) state.frames.removeAt(state.frames.size - 1)
                }

                state.contextStack.isNotEmpty() && state.contextStack.last() == '(' && ch.isLetterOrDigit() -> {
                    val savedPos = state.pos
                    val key = state.readIdent()
                    state.skipWs()
                    if (state.pos < state.len && state.input[state.pos] == '=') {
                        state.pos++ // skip =
                        state.skipWs()
                        if (state.pos < state.len && (state.isObjectStart() || state.input[state.pos] == '[')) {
                            state.pendingKey = key
                            rows.add(TreeRow(state.indent, listOf(
                                TextSegment(key, SegmentColor.KEY),
                                TextSegment(" = ", SegmentColor.SEPARATOR)
                            ), state.childPath(key)))
                        } else {
                            val value = state.readRaw()
                            state.skipWs()
                            if (state.pos < state.len && state.input[state.pos] == ',') state.pos++
                            rows.add(TreeRow(state.indent, listOf(
                                TextSegment(key, SegmentColor.KEY),
                                TextSegment(" = ", SegmentColor.SEPARATOR),
                                TextSegment(value, SegmentColor.VALUE)
                            ), state.childPath(key)))
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
                        rows.add(TreeRow(state.indent, listOf(TextSegment(value, SegmentColor.VALUE)), state.nextElementPath()))
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

private class PathFrame(val prefix: String, val isList: Boolean) {
    var index = 0
}

private class TokenizerState(val input: String) {
    var pos = 0
    val len = input.length
    var indent = 0
    val contextStack = ArrayDeque<Char>()
    val frames = mutableListOf<PathFrame>()
    var pendingKey: String? = null

    fun childPath(key: String): String {
        val prefix = frames.lastOrNull()?.prefix.orEmpty()
        return if (prefix.isEmpty()) key else "$prefix.$key"
    }

    fun nextElementPath(): String? {
        val frame = frames.lastOrNull() ?: return null
        if (!frame.isList) return null
        val path = "${frame.prefix}[${frame.index}]"
        frame.index++
        return path
    }

        fun enterContainer(rootName: String?): String? {
        val key = pendingKey
        pendingKey = null
        return when {
            key != null -> childPath(key)
            frames.isEmpty() -> rootName
            else -> nextElementPath()
        }
    }

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
