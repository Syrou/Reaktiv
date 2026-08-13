package io.github.syrou.reaktiv.network.ktor

import io.github.syrou.reaktiv.core.util.currentTimeMillis
import io.github.syrou.reaktiv.introspection.network.NetworkBodyPart
import io.github.syrou.reaktiv.introspection.network.NetworkBodyProvider
import io.github.syrou.reaktiv.introspection.network.NetworkRequestCapture
import io.github.syrou.reaktiv.introspection.network.NetworkTap
import io.github.syrou.reaktiv.introspection.network.sliceOnCharBoundary
import io.ktor.client.call.HttpClientCall
import io.ktor.client.plugins.api.ClientPlugin
import io.ktor.client.plugins.api.Send
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.contentLength
import io.ktor.http.contentType
import io.ktor.http.content.OutgoingContent
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlinx.coroutines.Job

public class ReaktivNetworkInspectionConfig {
    public var captureBodies: Boolean = true
    public var maxBodyBytes: Int = 64 * 1024
    public var hardBodyLimitBytes: Long = 2L * 1024 * 1024
    public var redactedHeaders: Set<String> = setOf(
        "Authorization",
        "Cookie",
        "Set-Cookie",
        "Proxy-Authorization"
    )
    public var bodyRetentionCount: Int = 50
    public var bodyRetentionBytes: Long = 8L * 1024 * 1024
    public var shouldCaptureBody: (ContentType?) -> Boolean = { isTextualContent(it) }
}

@OptIn(ExperimentalAtomicApi::class)
private val requestCounter = AtomicLong(0L)

public val ReaktivNetworkInspection: ClientPlugin<ReaktivNetworkInspectionConfig> = createClientPlugin(
    "ReaktivNetworkInspection",
    ::ReaktivNetworkInspectionConfig
) {
    val config = pluginConfig
    val retention = BodyRetention(config.bodyRetentionCount, config.bodyRetentionBytes)
    val bodyProvider = installBodyProvider(retention)

    on(Send) { request ->
        if (!NetworkTap.hasListeners) return@on proceed(request)
        captureExchange(config, retention, request) { proceed(request) }
    }

    client.coroutineContext[Job]?.invokeOnCompletion {
        NetworkTap.removeBodyProvider(bodyProvider)
    }
}

@OptIn(ExperimentalAtomicApi::class)
private suspend fun captureExchange(
    config: ReaktivNetworkInspectionConfig,
    retention: BodyRetention,
    request: HttpRequestBuilder,
    proceed: suspend () -> HttpClientCall
): HttpClientCall {
    val id = "net-${currentTimeMillis()}-${requestCounter.addAndFetch(1L)}"
    val startedAt = currentTimeMillis()
    val method = request.method.value
    val urlString = request.url.buildString()
    val requestHeaders = request.headers.build()
    val body = request.body as? OutgoingContent
    val requestBodyBytes = if (config.captureBodies) {
        (body as? OutgoingContent.ByteArrayContent)?.let { content ->
            try {
                content.bytes()
            } catch (_: Exception) {
                null
            }
        }
    } else {
        null
    }
    val requestContentType = body?.contentType?.toString()
        ?: requestHeaders["Content-Type"]

    retention.remember(
        RetainedExchange(
            id = id,
            method = method,
            url = urlString,
            headers = requestHeaders.toCapturedMap(emptySet()),
            bodyBytes = requestBodyBytes?.takeIf { it.size <= config.hardBodyLimitBytes },
            contentType = requestContentType
        )
    )

    val base = NetworkRequestCapture(
        id = id,
        startedAtMs = startedAt,
        durationMs = 0,
        method = method,
        url = urlString,
        requestHeaders = requestHeaders.toCapturedMap(config.redactedHeaders),
        requestContentType = requestContentType,
        requestBody = requestBodyBytes?.let { decodeBounded(it, config.maxBodyBytes) },
        requestBodySize = requestBodyBytes?.size?.toLong() ?: 0L,
        requestBodyTruncated = (requestBodyBytes?.size ?: 0) > config.maxBodyBytes
    )

    val call = try {
        proceed()
    } catch (t: Throwable) {
        NetworkTap.emit(
            base.copy(
                durationMs = currentTimeMillis() - startedAt,
                error = t.message ?: t::class.simpleName ?: "request failed"
            )
        )
        throw t
    }

    val headersAt = currentTimeMillis()
    val response = call.response
    val responseContentType = response.contentType()
    val responseLength = response.contentLength()
    val shouldRead = config.captureBodies &&
        config.shouldCaptureBody(responseContentType) &&
        (responseLength == null || responseLength <= config.hardBodyLimitBytes)
    val responseText = if (shouldRead) {
        try {
            response.bodyAsText()
        } catch (_: Exception) {
            null
        }
    } else {
        null
    }
    val bodyReadAt = currentTimeMillis()
    val overHardLimit = responseText != null &&
        responseText.length.toLong() > config.hardBodyLimitBytes
    val responseBytes = if (responseText != null && !overHardLimit) {
        responseText.encodeToByteArray()
    } else {
        null
    }
    retention.attachResponse(id, responseBytes?.takeIf { it.size <= config.hardBodyLimitBytes })

    val previewBody = when {
        responseBytes != null -> decodeBounded(responseBytes, config.maxBodyBytes)
        responseText != null -> responseText.take(config.maxBodyBytes)
        else -> null
    }
    val measuredSize = responseLength
        ?: responseBytes?.size?.toLong()
        ?: responseText?.length?.toLong()
        ?: -1L

    NetworkTap.emit(
        base.copy(
            durationMs = currentTimeMillis() - startedAt,
            responseStatus = response.status.value,
            responseStatusText = response.status.description,
            responseHeaders = response.headers.toCapturedMap(config.redactedHeaders),
            responseContentType = responseContentType?.toString(),
            responseBody = previewBody,
            responseBodySize = measuredSize,
            responseBodyTruncated = overHardLimit ||
                (responseBytes?.size ?: 0) > config.maxBodyBytes,
            waitMs = headersAt - startedAt,
            downloadMs = if (shouldRead) bodyReadAt - headersAt else null
        )
    )
    return call
}

private fun installBodyProvider(retention: BodyRetention): NetworkBodyProvider {
    val provider = NetworkBodyProvider { requestId, part, offset, maxBytes ->
        val retained = retention.find(requestId)
        val bytes = when (part) {
            NetworkBodyPart.REQUEST -> retained?.bodyBytes
            NetworkBodyPart.RESPONSE -> retained?.responseBytes
        }
        bytes?.sliceOnCharBoundary(offset, maxBytes)
    }
    NetworkTap.addBodyProvider(provider)
    return provider
}

private fun Headers.toCapturedMap(redacted: Set<String>): Map<String, List<String>> =
    entries().associate { (key, values) ->
        key to if (redacted.any { it.equals(key, ignoreCase = true) }) {
            listOf("<redacted>")
        } else {
            values
        }
    }

private fun decodeBounded(bytes: ByteArray, maxBytes: Int): String {
    if (bytes.size <= maxBytes) return bytes.decodeToString()
    var end = maxBytes
    while (end > 0 && (bytes[end].toInt() and 0xC0) == 0x80) {
        end--
    }
    return bytes.copyOfRange(0, end).decodeToString()
}

internal fun isTextualContent(contentType: ContentType?): Boolean {
    if (contentType == null) return false
    val type = contentType.contentType
    val sub = contentType.contentSubtype
    if (type == "text") return sub != "event-stream"
    if (type == "application") {
        return sub == "json" || sub == "xml" || sub == "x-www-form-urlencoded" ||
            sub == "javascript" || sub.endsWith("+json") || sub.endsWith("+xml")
    }
    return false
}

internal class RetainedExchange(
    val id: String,
    val method: String,
    val url: String,
    val headers: Map<String, List<String>>,
    val bodyBytes: ByteArray?,
    val contentType: String?,
    val responseBytes: ByteArray? = null
) {
    val retainedBytes: Long
        get() = (bodyBytes?.size?.toLong() ?: 0L) + (responseBytes?.size?.toLong() ?: 0L)

    fun withResponse(bytes: ByteArray?): RetainedExchange =
        RetainedExchange(id, method, url, headers, bodyBytes, contentType, bytes)
}

@OptIn(ExperimentalAtomicApi::class)
internal class BodyRetention(
    private val capacity: Int,
    private val byteBudget: Long = Long.MAX_VALUE
) {
    private val retained = AtomicReference<List<RetainedExchange>>(emptyList())

    fun remember(request: RetainedExchange) {
        while (true) {
            val current = retained.load()
            if (retained.compareAndSet(current, evict(current + request))) return
        }
    }

    fun attachResponse(requestId: String, bytes: ByteArray?) {
        if (bytes == null) return
        while (true) {
            val current = retained.load()
            val index = current.indexOfLast { it.id == requestId }
            if (index < 0) return
            val updated = current.toMutableList()
            updated[index] = updated[index].withResponse(bytes)
            if (retained.compareAndSet(current, evict(updated))) return
        }
    }

    private fun evict(entries: List<RetainedExchange>): List<RetainedExchange> {
        var kept = if (entries.size > capacity) entries.drop(entries.size - capacity) else entries
        var total = kept.sumOf { it.retainedBytes }
        var dropFrom = 0
        while (total > byteBudget && dropFrom < kept.size - 1) {
            total -= kept[dropFrom].retainedBytes
            dropFrom++
        }
        if (dropFrom > 0) {
            kept = kept.drop(dropFrom)
        }
        return kept
    }

    fun find(requestId: String): RetainedExchange? =
        retained.load().lastOrNull { it.id == requestId }
}
