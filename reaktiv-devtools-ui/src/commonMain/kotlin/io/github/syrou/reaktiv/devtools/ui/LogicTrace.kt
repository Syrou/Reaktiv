package io.github.syrou.reaktiv.devtools.ui

import io.github.syrou.reaktiv.devtools.protocol.kind
import io.github.syrou.reaktiv.devtools.protocol.SpanKind
import io.github.syrou.reaktiv.core.tracing.LogicMethodCompleted
import io.github.syrou.reaktiv.core.tracing.LogicMethodFailed
import io.github.syrou.reaktiv.core.tracing.LogicMethodStart
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

internal class LogicTrace private constructor(val events: List<LogicMethodEvent>) {
    val startedByCallId: Map<String, LogicMethodEvent.Started> =
        events.filterIsInstance<LogicMethodEvent.Started>().associateBy { it.callId }
    val completedByCallId: Map<String, LogicMethodEvent.Completed> =
        events.filterIsInstance<LogicMethodEvent.Completed>().associateBy { it.callId }
    val failedByCallId: Map<String, LogicMethodEvent.Failed> =
        events.filterIsInstance<LogicMethodEvent.Failed>().associateBy { it.callId }

    val started: List<LogicMethodStart> = events.filterIsInstance<LogicMethodEvent.Started>().map { it.event }
    val completed: List<LogicMethodCompleted> = events.filterIsInstance<LogicMethodEvent.Completed>().map { it.event }
    val failed: List<LogicMethodFailed> = events.filterIsInstance<LogicMethodEvent.Failed>().map { it.event }

    fun depths(parentCounts: (LogicMethodEvent.Started) -> Boolean = { true }): (String) -> Int {
        val cache = mutableMapOf<String, Int>()

        fun depthOf(callId: String, guard: MutableSet<String>): Int {
            cache[callId]?.let { return it }
            if (!guard.add(callId)) return 0
            val parentId = startedByCallId[callId]?.event?.parentCallId
            val parent = parentId?.let { startedByCallId[it] }
            val depth = if (parent != null && parentCounts(parent)) depthOf(parentId, guard) + 1 else 0
            cache[callId] = depth
            return depth
        }

        return { callId -> depthOf(callId, mutableSetOf()) }
    }

    @OptIn(ExperimentalAtomicApi::class)
    companion object {
        private val latest = AtomicReference<LogicTrace?>(null)

        fun of(events: List<LogicMethodEvent>): LogicTrace {
            latest.load()?.takeIf { it.events === events }?.let { return it }
            return LogicTrace(events).also { latest.store(it) }
        }
    }
}

internal val LogicMethodEvent.Started.kind: SpanKind get() = event.kind
