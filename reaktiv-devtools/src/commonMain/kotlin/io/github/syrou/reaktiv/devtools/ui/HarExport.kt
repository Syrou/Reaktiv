package io.github.syrou.reaktiv.devtools.ui

import io.github.syrou.reaktiv.introspection.network.NetworkRequestCapture
import kotlin.time.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal fun epochMillisToIso8601(epochMillis: Long): String =
    Instant.fromEpochMilliseconds(epochMillis).toString()

internal fun List<NetworkEventRow>.toHar(): JsonObject = buildJsonObject {
    put("log", buildJsonObject {
        put("version", "1.2")
        put("creator", buildJsonObject {
            put("name", "Reaktiv DevTools")
            put("version", "1.2")
        })
        put("pages", JsonArray(emptyList()))
        put("entries", buildJsonArray {
            this@toHar.sortedBy { it.event.startedAtMs }.forEach { row ->
                add(row.event.toHarEntry())
            }
        })
    })
}

private fun NetworkRequestCapture.toHarEntry(): JsonObject = buildJsonObject {
    put("startedDateTime", epochMillisToIso8601(startedAtMs))
    put("time", durationMs)
    put("request", buildJsonObject {
        put("method", method)
        put("url", url)
        put("httpVersion", "HTTP/1.1")
        put("cookies", JsonArray(emptyList()))
        put("headers", requestHeaders.toHarHeaders())
        put("queryString", url.toHarQuery())
        if (requestBody != null) {
            put("postData", buildJsonObject {
                put("mimeType", requestContentType ?: "application/octet-stream")
                put("text", requestBody)
            })
        }
        put("headersSize", -1)
        put("bodySize", requestBodySize)
    })
    put("response", buildJsonObject {
        put("status", responseStatus ?: 0)
        put("statusText", responseStatusText ?: error ?: "")
        put("httpVersion", "HTTP/1.1")
        put("cookies", JsonArray(emptyList()))
        put("headers", responseHeaders.toHarHeaders())
        put("content", buildJsonObject {
            put("size", responseBodySize)
            put("mimeType", responseContentType ?: "application/octet-stream")
            if (responseBody != null) {
                put("text", responseBody)
            }
            if (responseBodyTruncated) {
                put("comment", "truncated by Reaktiv capture")
            }
        })
        put("redirectURL", "")
        put("headersSize", -1)
        put("bodySize", responseBodySize)
    })
    put("cache", JsonObject(emptyMap()))
    put("timings", buildJsonObject {
        put("send", 0)
        put("wait", waitMs ?: durationMs)
        put("receive", downloadMs ?: 0)
    })
}

private fun Map<String, List<String>>.toHarHeaders(): JsonArray = buildJsonArray {
    forEach { (name, values) ->
        values.forEach { value ->
            add(buildJsonObject {
                put("name", name)
                put("value", value)
            })
        }
    }
}

private fun String.toHarQuery(): JsonArray = buildJsonArray {
    val query = substringAfter('?', "")
    if (query.isEmpty()) return@buildJsonArray
    query.split('&').forEach { pair ->
        if (pair.isEmpty()) return@forEach
        add(buildJsonObject {
            put("name", pair.substringBefore('='))
            put("value", pair.substringAfter('=', ""))
        })
    }
}
