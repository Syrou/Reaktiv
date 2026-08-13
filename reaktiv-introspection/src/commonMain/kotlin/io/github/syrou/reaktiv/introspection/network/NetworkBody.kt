package io.github.syrou.reaktiv.introspection.network

import kotlinx.serialization.Serializable

@Serializable
public enum class NetworkBodyPart {
    REQUEST,
    RESPONSE
}

@Serializable
public data class NetworkBodySlice(
    val content: String,
    val offset: Int,
    val nextOffset: Int,
    val totalBytes: Int,
    val isLast: Boolean
)

public fun interface NetworkBodyProvider {
    public fun slice(
        requestId: String,
        part: NetworkBodyPart,
        offset: Int,
        maxBytes: Int
    ): NetworkBodySlice?
}

public fun ByteArray.sliceOnCharBoundary(offset: Int, maxBytes: Int): NetworkBodySlice {
    val start = offset.coerceIn(0, size)
    var end = minOf(start.toLong() + maxBytes.coerceAtLeast(1), size.toLong()).toInt()
    if (end < size) {
        while (end > start && (this[end].toInt() and 0xC0) == 0x80) {
            end--
        }
        if (end == start) {
            end = minOf(start + maxBytes.coerceAtLeast(1), size)
        }
    }
    return NetworkBodySlice(
        content = copyOfRange(start, end).decodeToString(),
        offset = start,
        nextOffset = end,
        totalBytes = size,
        isLast = end >= size
    )
}
