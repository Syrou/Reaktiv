package io.github.syrou.reaktiv.introspection

import io.github.syrou.reaktiv.introspection.network.NetworkRequestCapture

/**
 * Size budgets for anything assembled into a single DevTools message.
 *
 * Every fan-out point that batches a variable number of records shares these numbers, so a frame
 * stays in the same range whether it carries actions, network exchanges or log lines. Counting
 * records instead was a reasonable proxy while records were small and uniform, but network
 * exchanges carry full request and response bodies and are orders of magnitude heavier than a logic
 * event, so a count-based cut says nothing useful about the resulting frame.
 *
 * Usage:
 * ```kotlin
 * var budget = WireBudget.MAX_PAYLOAD_BYTES
 * for (exchange in exchanges) {
 *     budget -= exchange.approximateWireBytes()
 *     if (budget <= 0) break
 * }
 * ```
 */
public object WireBudget {

    /**
     * Target ceiling for one message payload.
     *
     * Chosen to stay well inside the message limits platform websocket clients impose, notably
     * Darwin's 1 MiB default which applies per message rather than per frame, while remaining large
     * enough that a single oversized record does not need splitting.
     */
    public const val MAX_PAYLOAD_BYTES: Int = 1 * 1024 * 1024

    /**
     * Slice size for streaming a single body that exceeds [MAX_PAYLOAD_BYTES] on its own.
     */
    public const val BODY_CHUNK_BYTES: Int = 64 * 1024
}

/**
 * Rough serialized size of this exchange, used to decide where to cut a batch.
 *
 * Counts the fields that actually vary by orders of magnitude, bodies and urls and headers, rather
 * than serializing to measure. An estimate is enough: the budget exists to keep frames in a sane
 * range, not to hit an exact byte count.
 */
public fun NetworkRequestCapture.approximateWireBytes(): Int {
    var total = FIXED_EXCHANGE_OVERHEAD
    total += url.length
    total += requestBody?.length ?: 0
    total += responseBody?.length ?: 0
    total += error?.length ?: 0
    requestHeaders.forEach { (name, values) ->
        total += name.length + values.sumOf { it.length + HEADER_ENTRY_OVERHEAD }
    }
    responseHeaders.forEach { (name, values) ->
        total += name.length + values.sumOf { it.length + HEADER_ENTRY_OVERHEAD }
    }
    return total
}

private const val FIXED_EXCHANGE_OVERHEAD = 256

private const val HEADER_ENTRY_OVERHEAD = 8
