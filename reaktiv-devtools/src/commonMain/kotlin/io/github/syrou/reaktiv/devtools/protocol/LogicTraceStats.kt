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
    val maxConcurrent: Int = 0,
    val peakThreads: Set<String> = emptySet(),
    val sourceFile: String? = null,
    val lineNumber: Int? = null,
    val githubSourceUrl: String? = null,
    val failureType: String? = null,
    val failureMessage: String? = null
) {
    public val methodIdentifier: String get() = "$logicClass.$methodName"

    public val failureSummary: String?
        get() = when {
            failureType == null -> null
            failureMessage.isNullOrBlank() -> failureType
            else -> "$failureType: $failureMessage"
        }
    public val avgMs: Long get() = if (finished > 0) totalMs / finished else 0L
    public val inFlight: Int get() = calls - finished
    public val runsOnMainThread: Boolean get() = threads.any { isMainThread(it) }
    public val isCongested: Boolean get() = maxConcurrent >= CONGESTION_PEAK_THRESHOLD

    public val location: String?
        get() {
            val file = sourceFile?.substringAfterLast('/')?.substringAfterLast('\\') ?: return null
            return if (lineNumber != null) "$file:$lineNumber" else file
        }

    public val labelWithLocation: String
        get() = location?.let { "$methodIdentifier ($it)" } ?: methodIdentifier

    public val congestionReason: String?
        get() = when {
            !isCongested -> null
            peakThreads.size == 1 -> "$maxConcurrent calls interleaving on ${peakThreads.first()}"
            peakThreads.isNotEmpty() -> "$maxConcurrent concurrent calls across ${peakThreads.joinToString(", ")}"
            else -> "$maxConcurrent concurrent calls"
        }
}

public const val CONGESTION_PEAK_THRESHOLD: Int = 3

public const val DISPATCH_TRACE_CLASS: String = "StoreDispatch"

public const val DISPATCH_QUEUE_WAIT_WARN_MS: Long = 100L

public data class DispatchStats(
    val processedActions: Int,
    val avgQueueWaitMs: Long,
    val maxQueueWaitMs: Long,
    val maxQueueDepth: Int,
    val totalProcessMs: Long
) {
    public val isCongested: Boolean get() = maxQueueWaitMs >= DISPATCH_QUEUE_WAIT_WARN_MS
}

public fun aggregateDispatchStats(
    started: List<LogicMethodStart>,
    completed: List<LogicMethodCompleted>,
    failed: List<LogicMethodFailed>
): DispatchStats? {
    val dispatchStarts = started.distinctBy { it.callId }.filter { it.logicClass == DISPATCH_TRACE_CLASS }
    if (dispatchStarts.isEmpty()) return null
    val dispatchCallIds = dispatchStarts.mapTo(mutableSetOf()) { it.callId }

    var totalWait = 0L
    var maxWait = 0L
    var maxDepth = 0
    for (start in dispatchStarts) {
        val wait = start.params["queueWaitMs"]?.toLongOrNull() ?: 0L
        val depth = start.params["queueDepth"]?.toIntOrNull() ?: 0
        totalWait += wait
        if (wait > maxWait) maxWait = wait
        if (depth > maxDepth) maxDepth = depth
    }

    val totalProcess = completed.distinctBy { it.callId }
        .filter { it.callId in dispatchCallIds }
        .sumOf { it.durationMs } +
        failed.distinctBy { it.callId }
            .filter { it.callId in dispatchCallIds }
            .sumOf { it.durationMs }

    return DispatchStats(
        processedActions = dispatchStarts.size,
        avgQueueWaitMs = totalWait / dispatchStarts.size,
        maxQueueWaitMs = maxWait,
        maxQueueDepth = maxDepth,
        totalProcessMs = totalProcess
    )
}

public const val STALL_TRACE_CLASS: String = "MainThreadWatchdog"

public fun isMainThread(thread: String): Boolean {
    val normalized = thread.lowercase()
    return normalized == "main" || normalized.startsWith("main ") || normalized.contains("main thread")
}

public data class StallGroup(
    val stack: String?,
    val count: Int,
    val worstMs: Long
)

public fun aggregateStalls(
    started: List<LogicMethodStart>,
    completed: List<LogicMethodCompleted>
): List<StallGroup> {
    val stallStarts = started.distinctBy { it.callId }.filter { it.logicClass == STALL_TRACE_CLASS }
    if (stallStarts.isEmpty()) return emptyList()
    val durationByCallId = completed.distinctBy { it.callId }.associate { it.callId to it.durationMs }

    val durationsByStack = LinkedHashMap<String?, MutableList<Long>>()
    for (start in stallStarts) {
        val stack = start.params["stack"]?.takeIf { it.isNotBlank() }
        val duration = durationByCallId[start.callId] ?: 0L
        durationsByStack.getOrPut(stack) { mutableListOf() }.add(duration)
    }

    return durationsByStack.map { (stack, durations) ->
        StallGroup(stack = stack, count = durations.size, worstMs = durations.maxOrNull() ?: 0L)
    }.sortedByDescending { it.worstMs }
}

public fun stallCulprits(
    started: List<LogicMethodStart>,
    completed: List<LogicMethodCompleted>,
    failed: List<LogicMethodFailed>
): List<MethodStats> {
    val stallStarts = started.distinctBy { it.callId }.filter { it.logicClass == STALL_TRACE_CLASS }
    if (stallStarts.isEmpty()) return emptyList()
    val stallCallIds = stallStarts.mapTo(mutableSetOf()) { it.callId }
    val windows = completed.distinctBy { it.callId }
        .filter { it.callId in stallCallIds }
        .map { (it.timestampMs - it.durationMs) to it.timestampMs }
    if (windows.isEmpty()) return emptyList()

    val durationByCallId = HashMap<String, Long>()
    for (c in completed) durationByCallId[c.callId] = c.durationMs
    for (f in failed) if (f.callId !in durationByCallId) durationByCallId[f.callId] = f.durationMs

    val culpritKeys = started.distinctBy { it.callId }
        .filter { s ->
            val thread = s.thread
            if (s.logicClass == STALL_TRACE_CLASS || s.logicClass == DISPATCH_TRACE_CLASS) return@filter false
            if (thread == null || !isMainThread(thread)) return@filter false
            val callStart = s.timestampMs
            val callEnd = s.timestampMs + (durationByCallId[s.callId] ?: 0L)
            windows.any { (from, to) -> callStart <= to && callEnd >= from }
        }
        .mapTo(mutableSetOf()) { it.logicClass to it.methodName }

    if (culpritKeys.isEmpty()) return emptyList()
    return aggregateLogicStats(started, completed, failed)
        .filter { (it.logicClass to it.methodName) in culpritKeys }
}

public data class ThreadStats(
    val thread: String,
    val calls: Int,
    val busyMs: Long,
    val maxConcurrent: Int,
    val contenders: Set<String> = emptySet()
) {
    public val isMain: Boolean get() = isMainThread(thread)
    public val isCongested: Boolean get() = maxConcurrent >= CONGESTION_PEAK_THRESHOLD

    public val contentionReason: String?
        get() = when {
            !isCongested -> null
            contenders.isNotEmpty() -> "${contenders.joinToString(", ")} overlapping"
            else -> "$maxConcurrent overlapping calls"
        }
}

private class Interval(val startMs: Long, val endMs: Long, val label: String = "")

private class OverlapDetail(val peak: Int, val contenders: Set<String>)

private fun peakOverlapDetail(intervals: List<Interval>): OverlapDetail {
    if (intervals.isEmpty()) return OverlapDetail(0, emptySet())
    val events = ArrayList<Triple<Long, Int, String>>(intervals.size * 2)
    for (interval in intervals) {
        events.add(Triple(interval.startMs, 1, interval.label))
        events.add(Triple(interval.endMs, -1, interval.label))
    }
    events.sortWith(compareBy({ it.first }, { it.second }))
    val active = LinkedHashMap<String, Int>()
    var current = 0
    var peak = 0
    var peakLabels = emptySet<String>()
    for ((_, delta, label) in events) {
        if (delta > 0) {
            current += 1
            active[label] = (active[label] ?: 0) + 1
            if (current > peak) {
                peak = current
                peakLabels = active.keys.filter { it.isNotEmpty() }.toSet()
            }
        } else {
            current -= 1
            val remaining = (active[label] ?: 0) - 1
            if (remaining <= 0) active.remove(label) else active[label] = remaining
        }
    }
    return OverlapDetail(peak, peakLabels)
}

public fun aggregateLogicStats(
    started: List<LogicMethodStart>,
    completed: List<LogicMethodCompleted>,
    failed: List<LogicMethodFailed>
): List<MethodStats> {
    val uniqueStarted = started.distinctBy { it.callId }
    val uniqueCompleted = completed.distinctBy { it.callId }
    val uniqueFailed = failed.distinctBy { it.callId }
    val startByCallId = HashMap<String, LogicMethodStart>(uniqueStarted.size)
    val callCounts = LinkedHashMap<Pair<String, String>, Int>()
    for (start in uniqueStarted) {
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
        acc.intervals.add(
            Interval(start.timestampMs, start.timestampMs + durationMs, start.thread ?: "")
        )
    }

    for (completion in uniqueCompleted) record(completion.callId, completion.durationMs, isFailure = false)
    for (failure in uniqueFailed) record(failure.callId, failure.durationMs, isFailure = true)

    val failureByKey = HashMap<Pair<String, String>, LogicMethodFailed>()
    for (failure in uniqueFailed) {
        val start = startByCallId[failure.callId] ?: continue
        failureByKey[start.logicClass to start.methodName] = failure
    }

    val threadsByKey = HashMap<Pair<String, String>, MutableSet<String>>()
    val dispatchersByKey = HashMap<Pair<String, String>, MutableSet<String>>()
    val locationByKey = HashMap<Pair<String, String>, LogicMethodStart>()
    for (start in uniqueStarted) {
        val key = start.logicClass to start.methodName
        start.thread?.let { threadsByKey.getOrPut(key) { mutableSetOf() }.add(it) }
        start.dispatcher?.let { dispatchersByKey.getOrPut(key) { mutableSetOf() }.add(it) }
        if (start.sourceFile != null && key !in locationByKey) {
            locationByKey[key] = start
        }
    }

    return callCounts.map { (key, calls) ->
        val acc = accumulators[key]
        val overlap = acc?.let { peakOverlapDetail(it.intervals) }
        val location = locationByKey[key]
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
            maxConcurrent = overlap?.peak ?: 0,
            peakThreads = overlap?.contenders ?: emptySet(),
            sourceFile = location?.sourceFile,
            lineNumber = location?.lineNumber,
            githubSourceUrl = location?.githubSourceUrl,
            failureType = failureByKey[key]?.exceptionType,
            failureMessage = failureByKey[key]?.exceptionMessage
        )
    }.sortedByDescending { it.totalMs }
}

public fun aggregateThreadStats(
    started: List<LogicMethodStart>,
    completed: List<LogicMethodCompleted>,
    failed: List<LogicMethodFailed>
): List<ThreadStats> {
    val uniqueStarted = started.distinctBy { it.callId }
    val uniqueCompleted = completed.distinctBy { it.callId }
    val uniqueFailed = failed.distinctBy { it.callId }
    val startByCallId = uniqueStarted.associateBy { it.callId }
    val callsByThread = LinkedHashMap<String, Int>()
    for (start in uniqueStarted) {
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
            .add(Interval(start.timestampMs, start.timestampMs + durationMs, "${start.logicClass}.${start.methodName}"))
    }

    for (completion in uniqueCompleted) record(completion.callId, completion.durationMs)
    for (failure in uniqueFailed) record(failure.callId, failure.durationMs)

    return callsByThread.map { (thread, calls) ->
        val overlap = peakOverlapDetail(intervalsByThread[thread] ?: emptyList())
        ThreadStats(
            thread = thread,
            calls = calls,
            busyMs = busyByThread[thread] ?: 0L,
            maxConcurrent = overlap.peak,
            contenders = overlap.contenders
        )
    }.sortedByDescending { it.busyMs }
}
