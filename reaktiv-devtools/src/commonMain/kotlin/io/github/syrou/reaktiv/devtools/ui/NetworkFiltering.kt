package io.github.syrou.reaktiv.devtools.ui

import kotlinx.serialization.Serializable

@Serializable
internal enum class NetworkSort(val label: String) {
    NEWEST("Newest"),
    SLOWEST("Slowest"),
    LARGEST("Largest")
}

@Serializable
internal data class NetworkFilter(
    val query: String = "",
    val failuresOnly: Boolean = false,
    val methods: Set<String> = emptySet(),
    val sort: NetworkSort = NetworkSort.NEWEST
) {
    val isActive: Boolean
        get() = query.isNotBlank() || failuresOnly || methods.isNotEmpty() || sort != NetworkSort.NEWEST
}

internal fun List<NetworkEventRow>.mergeNetworkEvents(
    incoming: List<NetworkEventRow>
): List<NetworkEventRow> {
    if (incoming.isEmpty()) return this
    val positionByKey = HashMap<Pair<String, String>, Int>(size + incoming.size)
    forEachIndexed { index, row -> positionByKey[row.mergeKey] = index }
    val merged = ArrayList(this)
    incoming.forEach { row ->
        val existing = positionByKey[row.mergeKey]
        if (existing != null) {
            merged[existing] = row
        } else {
            positionByKey[row.mergeKey] = merged.size
            merged.add(row)
        }
    }
    return merged
}

private val NetworkEventRow.mergeKey: Pair<String, String>
    get() = clientId to event.id

internal fun networkHost(url: String): String {
    val withoutScheme = url.substringAfter("://", url)
    return withoutScheme.substringBefore('/').substringBefore('?')
}

internal fun List<NetworkEventRow>.applyNetworkFilter(filter: NetworkFilter): List<NetworkEventRow> {
    val query = filter.query.trim()
    val matched = filter { row ->
        val event = row.event
        val methodOk = filter.methods.isEmpty() || event.method.uppercase() in filter.methods
        val failureOk = !filter.failuresOnly || event.isFailure
        val queryOk = query.isEmpty() ||
            event.url.contains(query, ignoreCase = true) ||
            event.method.contains(query, ignoreCase = true) ||
            event.responseStatus?.toString()?.contains(query) == true ||
            event.error?.contains(query, ignoreCase = true) == true ||
            event.decodeError?.contains(query, ignoreCase = true) == true
        methodOk && failureOk && queryOk
    }
    return when (filter.sort) {
        NetworkSort.NEWEST -> matched.sortedByDescending { it.event.startedAtMs }
        NetworkSort.SLOWEST -> matched.sortedByDescending { it.event.durationMs }
        NetworkSort.LARGEST -> matched.sortedByDescending {
            maxOf(it.event.responseBodySize, 0L) + it.event.requestBodySize
        }
    }
}

@Serializable
internal data class EndpointStats(
    val method: String,
    val host: String,
    val path: String,
    val calls: Int,
    val failures: Int,
    val medianMs: Long,
    val slowestMs: Long,
    val totalBytes: Long
)

internal fun List<NetworkEventRow>.endpointStats(): List<EndpointStats> =
    groupBy { row ->
        val url = row.event.url
        Triple(row.event.method, networkHost(url), networkPath(url))
    }.map { (key, rows) ->
        val durations = rows.map { it.event.durationMs }.sorted()
        EndpointStats(
            method = key.first,
            host = key.second,
            path = key.third,
            calls = rows.size,
            failures = rows.count { it.event.isFailure },
            medianMs = durations[durations.size / 2],
            slowestMs = durations.last(),
            totalBytes = rows.sumOf {
                maxOf(it.event.responseBodySize, 0L) + it.event.requestBodySize
            }
        )
    }.sortedWith(compareByDescending<EndpointStats> { it.failures }.thenByDescending { it.calls })

internal fun networkPath(url: String): String {
    val withoutScheme = url.substringAfter("://", url)
    val slash = withoutScheme.indexOf('/')
    val path = if (slash < 0) "/" else withoutScheme.substring(slash)
    return path.substringBefore('?').ifEmpty { "/" }
}
