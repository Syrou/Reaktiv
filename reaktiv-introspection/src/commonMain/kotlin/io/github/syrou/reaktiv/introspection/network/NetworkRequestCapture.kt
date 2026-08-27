package io.github.syrou.reaktiv.introspection.network

import kotlinx.serialization.Serializable

@Serializable
public data class NetworkRequestCapture(
    val id: String,
    val startedAtMs: Long,
    val durationMs: Long,
    val method: String,
    val url: String,
    val requestHeaders: Map<String, List<String>> = emptyMap(),
    val requestContentType: String? = null,
    val requestBody: String? = null,
    val requestBodySize: Long = 0,
    val requestBodyTruncated: Boolean = false,
    val responseStatus: Int? = null,
    val responseStatusText: String? = null,
    val responseHeaders: Map<String, List<String>> = emptyMap(),
    val responseContentType: String? = null,
    val responseBody: String? = null,
    val responseBodySize: Long = -1,
    val responseBodyTruncated: Boolean = false,
    val error: String? = null,
    val waitMs: Long? = null,
    val downloadMs: Long? = null,
    val decodeError: String? = null
) {
    val isFailure: Boolean
        get() = error != null || decodeError != null || (responseStatus ?: 0) >= 400
}
