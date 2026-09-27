package io.github.syrou.reaktiv.devtools.ui.components

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import kotlinx.serialization.json.Json

internal fun looksLikeJson(text: String): Boolean {
    val trimmed = text.trim()
    if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) return false
    return runCatching { Json.parseToJsonElement(trimmed) }.isSuccess
}

@Composable
internal fun StructuredValueViewer(
    text: String,
    modifier: Modifier = Modifier,
    previousText: String? = null,
    searchQuery: String = "",
    showDiff: Boolean = false,
    header: (LazyListScope.() -> Unit)? = null
) {
    val json = remember(text) { looksLikeJson(text) }
    if (json) {
        JsonTreeViewer(
            jsonString = text,
            previousJsonString = previousText,
            searchQuery = searchQuery,
            showDiff = showDiff,
            modifier = modifier,
            header = header
        )
    } else {
        KotlinObjectTreeViewer(text = text, modifier = modifier, header = header)
    }
}
