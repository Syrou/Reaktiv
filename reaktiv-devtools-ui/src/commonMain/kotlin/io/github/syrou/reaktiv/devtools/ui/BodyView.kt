package io.github.syrou.reaktiv.devtools.ui

import kotlinx.serialization.json.Json

internal enum class BodyRender {
    TREE,
    RAW,
    ABSENT
}

internal data class BodyView(
    val text: String?,
    val render: BodyRender,
    val treeAvailable: Boolean,
    val streaming: Boolean,
    val note: String?
)

internal fun parsesAsJson(body: String): Boolean {
    val trimmed = body.trim()
    if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) return false
    return runCatching { Json.parseToJsonElement(trimmed) }.isSuccess
}

internal fun resolveBodyView(
    preview: String?,
    load: NetworkBodyLoad?,
    truncated: Boolean,
    contentType: String?
): BodyView {
    val streaming = load != null && load.loading
    val complete = load != null && load.complete && !load.unavailable
    val text = when {
        load != null && (complete || load.receivedBytes > 0) -> load.text
        else -> preview
    }
    if (text == null) {
        return BodyView(null, BodyRender.ABSENT, false, streaming, null)
    }

    val parses = parsesAsJson(text)
    val claimsJson = contentType?.contains("json", ignoreCase = true) == true
    val stillPartial = truncated && !complete

    val note = when {
        load != null && load.capturedOnly ->
            "This session did not capture the full body. Showing the preview it recorded."
        load != null && load.unavailable ->
            "The full body is no longer retained on the device. Showing the captured preview."
        streaming && load.receivedBytes == 0 ->
            "Waiting for the device to send the body."
        claimsJson && !parses && !streaming && stillPartial ->
            "Only the preview is available, so it cannot be parsed as JSON."
        claimsJson && !parses && !streaming ->
            "Content type says JSON but it did not parse. Showing raw text."
        else -> null
    }

    return BodyView(
        text = text,
        render = if (parses) BodyRender.TREE else BodyRender.RAW,
        treeAvailable = parses,
        streaming = streaming,
        note = note
    )
}
