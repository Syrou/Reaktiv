package io.github.syrou.reaktiv.devtools.protocol

import io.github.syrou.reaktiv.introspection.network.NetworkRequestCapture

public object CurlFormatter {

    public fun toCurl(event: NetworkRequestCapture): String {
        val parts = mutableListOf("curl -X ${event.method} '${escapeSingleQuotes(event.url)}'")
        event.requestHeaders.forEach { (key, values) ->
            values.forEach { value ->
                parts.add("-H '${escapeSingleQuotes("$key: $value")}'")
            }
        }
        val contentType = event.requestContentType
        if (contentType != null && event.requestHeaders.keys.none { it.equals("Content-Type", ignoreCase = true) }) {
            parts.add("-H '${escapeSingleQuotes("Content-Type: $contentType")}'")
        }
        event.requestBody?.let { body ->
            parts.add("--data-raw '${escapeSingleQuotes(body)}'")
        }
        return parts.joinToString(" \\\n  ")
    }

    private fun escapeSingleQuotes(value: String): String = value.replace("'", "'\\''")
}
