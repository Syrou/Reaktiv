package io.github.syrou.reaktiv.devtools.protocol

import io.github.syrou.reaktiv.core.tracing.LogicMethodCompleted
import io.github.syrou.reaktiv.core.tracing.LogicMethodFailed
import io.github.syrou.reaktiv.core.tracing.LogicMethodStart

public data class MethodStats(
    val logicClass: String,
    val methodName: String,
    val calls: Int,
    val finished: Int,
    val failures: Int,
    val totalMs: Long,
    val maxMs: Long,
    val threads: Set<String> = emptySet(),
    val dispatchers: Set<String> = emptySet(),
    val maxConcurrent: Int = 0
) {
    public val methodIdentifier: String get() = "$logicClass.$methodName"
    public val avgMs: Long get() = if (finished > 0) totalMs / finished else 0L
    public val inFlight: Int get() = calls - finished
    public val runsOnMainThread: Boolean get() = threads.any { isMainThread(it) }
    public val isCongested: Boolean get() = maxConcurrent >= CONGESTION_PEAK_THRESHOLD
}

public const val CONGESTION_PEAK_THRESHOLD: Int = 3

public fun isMainThread(thread: String): Boolean {
    val normalized = thread.lowercase()
    return normalized == "main" || normalized.startsWith("main ") || normalized.contains("main thread")
}

public data class ThreadStats(
    val thread: String,
    val calls: Int,
    val busyMs: Long,
    val maxConcurrent: Int
) {
    public val isMain: Boolean get() = isMainThread(thread)
    public val isCongested: Boolean get() = maxConcurrent >= CONGESTION_PEAK_THRESHOLD
}

private class Interval(val startMs: Long, val endMs: Long)

private fun peakOverlap(intervals: List<Interval>): Int {
    if (intervals.isEmpty()) return 0
    val events = ArrayList<Pair<Long, Int>>(intervals.size * 2)
    for (interval in intervals) {
        events.add(interval.startMs to 1)
        events.add(interval.endMs to -1)
    }
    events.sortWith(compareBy({ it.first }, { it.second }))
    var current = 0
    var peak = 0
    for ((_, delta) in events) {
        current += delta
        if (current > peak) peak = current
    }
    return peak
}

public fun aggregateLogicStats(
    started: List<LogicMethodStart>,
    completed: List<LogicMethodCompleted>,
    failed: List<LogicMethodFailed>
): List<MethodStats> {
    val startByCallId = HashMap<String, LogicMethodStart>(started.size)
    val callCounts = LinkedHashMap<Pair<String, String>, Int>()
    for (start in started) {
        val key = start.logicClass to start.methodName
        startByCallId[start.callId] = start
        callCounts[key] = (callCounts[key] ?: 0) + 1
    }

    class Accumulator {
        var finished = 0
        var failures = 0
        var totalMs = 0L
        var maxMs = 0L
        val intervals = mutableListOf<Interval>()
    }

    val accumulators = HashMap<Pair<String, String>, Accumulator>()

    fun record(callId: String, durationMs: Long, isFailure: Boolean) {
        val start = startByCallId[callId] ?: return
        val key = start.logicClass to start.methodName
        val acc = accumulators.getOrPut(key) { Accumulator() }
        acc.finished += 1
        if (isFailure) acc.failures += 1
        acc.totalMs += durationMs
        if (durationMs > acc.maxMs) acc.maxMs = durationMs
        acc.intervals.add(Interval(start.timestampMs, start.timestampMs + durationMs))
    }

    for (completion in completed) record(completion.callId, completion.durationMs, isFailure = false)
    for (failure in failed) record(failure.callId, failure.durationMs, isFailure = true)

    val threadsByKey = HashMap<Pair<String, String>, MutableSet<String>>()
    val dispatchersByKey = HashMap<Pair<String, String>, MutableSet<String>>()
    for (start in started) {
        val key = start.logicClass to start.methodName
        start.thread?.let { threadsByKey.getOrPut(key) { mutableSetOf() }.add(it) }
        start.dispatcher?.let { dispatchersByKey.getOrPut(key) { mutableSetOf() }.add(it) }
    }

    return callCounts.map { (key, calls) ->
        val acc = accumulators[key]
        MethodStats(
            logicClass = key.first,
            methodName = key.second,
            calls = calls,
            finished = acc?.finished ?: 0,
            failures = acc?.failures ?: 0,
            totalMs = acc?.totalMs ?: 0L,
            maxMs = acc?.maxMs ?: 0L,
            threads = threadsByKey[key] ?: emptySet(),
            dispatchers = dispatchersByKey[key] ?: emptySet(),
            maxConcurrent = acc?.let { peakOverlap(it.intervals) } ?: 0
        )
    }.sortedByDescending { it.totalMs }
}

public fun aggregateThreadStats(
    started: List<LogicMethodStart>,
    completed: List<LogicMethodCompleted>,
    failed: List<LogicMethodFailed>
): List<ThreadStats> {
    val startByCallId = started.associateBy { it.callId }
    val callsByThread = LinkedHashMap<String, Int>()
    for (start in started) {
        val thread = start.thread ?: continue
        callsByThread[thread] = (callsByThread[thread] ?: 0) + 1
    }

    val busyByThread = HashMap<String, Long>()
    val intervalsByThread = HashMap<String, MutableList<Interval>>()

    fun record(callId: String, durationMs: Long) {
        val start = startByCallId[callId] ?: return
        val thread = start.thread ?: return
        busyByThread[thread] = (busyByThread[thread] ?: 0L) + durationMs
        intervalsByThread.getOrPut(thread) { mutableListOf() }
            .add(Interval(start.timestampMs, start.timestampMs + durationMs))
    }

    for (completion in completed) record(completion.callId, completion.durationMs)
    for (failure in failed) record(failure.callId, failure.durationMs)

    return callsByThread.map { (thread, calls) ->
        ThreadStats(
            thread = thread,
            calls = calls,
            busyMs = busyByThread[thread] ?: 0L,
            maxConcurrent = peakOverlap(intervalsByThread[thread] ?: emptyList())
        )
    }.sortedByDescending { it.busyMs }
}
